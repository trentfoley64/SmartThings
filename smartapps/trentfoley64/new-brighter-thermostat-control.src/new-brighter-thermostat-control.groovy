/********

todo:
1) DONE 12/11/2015: move thermostat to parent
2) figure out how to provide default name of child smartapp
3) Figure out how to hide child apps from mobile app

*/

definition(
	name: "New Brighter Thermostat Control",
	namespace: "trentfoley64",
	author: "A. Trent Foley, Sr.",
	description: "Child SmartApp for Brighter Thermostat Controls.",
	category: "My Apps",
	parent: "trentfoley64:Brighter Thermostat Controls",
	iconUrl: "http://www.trentfoley.com/ST/icons/thermostat.png",
	iconX2Url: "http://www.trentfoley.com/ST/icons/thermostat@2x.png",
	iconX3Url: "http://www.trentfoley.com/ST/icons/thermostat@3x.png",
)

preferences {
	page(name: "schedulePage")
	page(name: "namePage")
}

def schedulePage() {
	dynamicPage(name: "schedulePage", title: "Brighter Thermostat Control", nextPage: "namePage", install: false, uninstall: true) {
		// Let user pick set points
		section("To these set points") {
			input "heatSetpoint", "decimal", title: "for Heating", default:70
			input "coolSetpoint", "decimal", title: "for Cooling", default:80
		}
		// Let user pick which days of week
		section("for Days of Week") {
			input "daysOfWeekList", "enum", title: "Which days?", required: true, multiple: true,
				options: ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']
		}
		// Let user specify Time of day
		section("Time of day") {
			input "timeOfDay", "time", title: "At this time of day"
		}
		// Let user specify presence rules
		section( "Presences") {
			input "anyMustBePresent", "capability.presenceSensor", title: "At least one must be present", multiple: true, required: false
			input "allMustBePresent", "capability.presenceSensor", title: "All must be present", multiple: true, required: false
			input "anyMustBeAbsent", "capability.presenceSensor", title: "At least one must be absent", multiple: true, required: false
			input "allMustBeAbsent", "capability.presenceSensor", title: "All must be absent", multiple: true, required: false
		}
		// Let user specify notification recipients
		section( "Notifications" ) {
			input "sendPushMessage", "enum", title: "Send a push notification?", options:["Yes", "No"], required: false, default: "No"
			input "sendSMSNumber", "phone", title: "Send a text message to this number:", required: false
		}
	}
}

def namePage() {
    if (!overrideLabel) {
        // if the user selects to not change the label, give a default label
        app.updateLabel(defaultLabel())
    }
    dynamicPage(name: "namePage") {
        if (overrideLabel) {
            section("Automation name") {
                label title: "Enter custom name", defaultValue: app.label, required: false
            }
        } else {
            section("Automation name") {
                paragraph app.label
            }
        }
        section {
            input "overrideLabel", "bool", title: "Edit automation name", defaultValue: "false", required: "false", submitOnChange: true
        }
    }
}

def installed() {
	log.debug "${app.label}: Installed with $settings"
	initialize()
}

def updated() {
	log.debug "${app.label}: Updated with $settings"
	initialize()
}

def initialize() {
	// get a date/time object for today at time specified by schedule
	def scheduleTime=timeToday(timeOfDay,location.timeZone)
    // compute next date/time for schedule determined by daysOfWeekList
	def nextRunTime=nextDayOfWeekDate(scheduleTime,daysOfWeekList)
    // Schedule thermostat control to run on computed date/time
    if(nextRunTime) {
		def msg="${app.label}: Scheduling next run for " + nextRunTime.format("EEE MMM dd yyyy HH:mm z", location.timeZone)
		log.debug msg
        // curious to see what this function does
        sendNotificationEvent msg
		runOnce(nextRunTime,runThermostatControl)
    }
    else {
    	// This should never happen.  Since both daysOfWeekList and time are required there is no
        // reason why nextRunTime should ever be null
    	def msg="Aborting Brighter Thermostat Control: ${app.label}." +
		        " Could not schedule next run for " + scheduleTime.format("EEE MMM dd yyyy HH:mm z", location.timeZone) +
                " for daysOfWeekList=" + daysOfWeekList
    	log.debug msg
        // Send a push message because this is a bad error
		sendPush msg
    }
}

def defaultLabel() {
	"${parent.thermostats} $timeOfDay ($daysOfWeekList) $anyMustBePresent $allMustBepresent $anyMustBeAbsent $allMustBeAbsent"
}

// return the next date, starting from startTime, that falls on a day of week in DaysOfWeekList
private nextDayOfWeekDate(startTime,daysOfWeekList) {
    // If it is early enough for schedule to run today, start from today; otherwise, start from tomorrow.
    // Also, there is a need to add a couple of minutes (2000ms) to now() to cover any sort of smartthings lag.
	def nextScheduleTime=((now()+2000) < startTime.time) ? startTime : startTime + 1
    // Check for up to 7 days ahead to find a date that matches our daysOfWeekList
	// use EEEE format to convert to long form day of week (Sunday,Monday,...Saturday)
    for(def i=0; i<7; i++) {
        if (daysOfWeekList.contains(nextScheduleTime.format("EEEE",location.timeZone))) {
        	// all done - found a date that falls on one of daysOfWeekList
        	return nextScheduleTime
        }
        // nextScheduleTime wasn't in our daysOfWeekList so skip to the next day
        nextScheduleTime=nextScheduleTime + 1
    }
    // If we get to this point, we are on the wrong planet and something is very wrong.
	log.debug "${app.label}: Wrong Planet Error. Unable to compute nextDayOfWeekDate for startTime=$startTime and daysOfWeekList=$daysOfWeekList."
	return null
}

def runThermostatControl() {
	// before doing anything else, schedule our next run
	initialize()
 	// Check presences
 	def passedChecks=checkPresences()
	// If we have hit the conditions to execute this then lets do it
	if (passedChecks) {
		def msg="${parent.thermostats} heat setpoint to '${heatSetpoint}' and cool setpoint to '${coolSetpoint}'"
		log.debug "${app.label}: $msg"
        sendNotificationEvent "${app.label}: $msg"
        // do the actual thermostat change
		parent.thermostats.setHeatingSetpoint(heatSetpoint)
		parent.thermostats.setCoolingSetpoint(coolSetpoint)
        // send any push / notification messages
		sendMessage msg
	}
    else {
    	def msg="${app.label}: passedChecks is false"
    	log.debug msg
        sendNotificationEvent msg
    }
}

private checkPresences() {
	// If defined, check anyMustBePresent
	if (anyMustBePresent) {
		// If anyMustBePresent does not contain anyone present, do not change thermostats
		if (!anyMustBePresent.currentValue('presence').contains('present')) {
			log.debug "${app.label}: change cancelled due to all of ${anyMustBePresent} being absent."
			return false
		}
	}
	// If defined, check allMustBePresent
	if (allMustBePresent) {
		// If allMustBePresent contains anyone not present, do not change thermostats
		if (allMustBePresent.currentValue('presence').contains('not present')) {
			log.debug "${app.label}: cancelled due to one of ${allMustBePresent} being absent."
			return false
		}
	}
	// If defined, check anyMustBeAbsent
	if (anyMustBeAbsent) {
		// If anyMustBeAbsent does not contain anyone not present, do not change thermostats
		if (!anyMustBeAbsent.currentValue('presence').contains('not present')) {
			log.debug "${app.label}: cancelled due to all of ${anyMustBeAbsent} being present."
			return false
		}
	}
	// If defined, check allMustBeAbsent
	if (allMustBeAbsent) {
		// If allMustBeAbsent contains anyone present, do not change thermostats
		if (allMustBeAbsent.currentValue('presence').contains('present')) {
			log.debug "${app.label}: cancelled due to one of ${allMustBeAbsent} being present."
			return false
		}
	}
    // If we've gotten to here, all checks have passed
    return true
} 

private sendMessage(msg) {
	// If user specified sending a push message, do so
	if (sendPushMessage == "Yes") {
		sendPush(msg)
	}
    // If user supplied a phone number, send an SMS
	if (sendSMSNumber) {
		sendSms(sendSMSNumber,msg)
	}
}