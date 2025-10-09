/**
 *  Copyright 2021
 *
 *  Based on the original work done by https://github.com/Wattos/hubitat
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Home Connect Hob (Child Device of Home Connection Integration)
 *
 *  Author: Rangner Ferraz Guimaraes (rferrazguimaraes)
 *  Mods:   Quality-of-life fixes for Hubitat switch events & option handling
 *  Date:   2021-11-28
 *  Version history:
 *    1.0 - Initial commit
 *    1.1 - Better handling of STOP events from event stream
 *    1.2 - Updating program when pressing 'Initialize' button
 *    1.3 - Map PowerState -> standard switch on/off, safer power calls,
 *          bool-pref fix, local STATUS parsing, JSON .toString()
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field Utils = Utils_create()
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
@Field static final Integer eventStreamDisconnectGracePeriod = 30

def driverVer() { return "1.3" }

metadata {
    definition(name: "Home Connect Hob", namespace: "rferrazguimaraes", author: "Rangner Ferraz Guimaraes") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Actuator"

        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"],
                              [name: "Message*", type:"STRING", description: "Message"]]
        command "startProgram"
        command "stopProgram"

        attribute "AvailableProgramsList", "JSON_OBJECT"
        attribute "AvailableOptionsList", "JSON_OBJECT"

        // BSH.Common.Status.RemoteControlActive
        attribute "RemoteControlActive", "enum", ["true", "false"]

        // BSH.Common.Status.RemoteControlStartAllowed
        attribute "RemoteControlStartAllowed", "enum", ["true", "false"]

        // BSH.Common.Status.OperationState
        attribute "OperationState", "enum", [
            "Inactive", "Ready", "DelayedStart", "Run", "Pause",
            "ActionRequired", "Finished", "Error", "Aborting"
        ]

        attribute "ActiveProgram", "string"
        attribute "SelectedProgram", "string"

        attribute "PowerState", "enum", ["Off", "On", "Standby"]

        attribute "EventPresentState", "enum", ["Event active", "Off", "Confirmed"]

        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        attribute "DriverVersion", "string"
    }

    preferences {
        section {
            List<String> availableProgramsList = getAvailableProgramsList()
            if (availableProgramsList.size() != 0) {
                input name:"selectedProgram", type:"enum", title: "Select Program", options:availableProgramsList
            }

            List<String> availableOptionList = getAvailableOptionsList()
            for (int i = 0; i < availableOptionList.size(); ++i) {
                String titleName = availableOptionList[i]
                String optionName = titleName.replaceAll("\\s","")
                input name:optionName, type:"bool", title: "${titleName}", defaultValue: false
            }

            input name: "logLevel", title: "Log Level", type: "enum",
                  options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

// ---------------- Commands ----------------

void startProgram() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) parent.startProgram(device, programToSelect.key)
    }
}

void stopProgram() {
    parent.stopProgram(device)
}

// ---------------- Lifecycle ----------------

void initialize() {
    Utils.toLogger("debug", "initialize()")
    intializeStatus()
}

void installed() {
    Utils.toLogger("debug", "installed()")
    intializeStatus()
}

void updated() {
    Utils.toLogger("debug", "updated()")
    setCurrentProgram()
    updateAvailableOptionsList()
    setCurrentProgramOptions()
}

void uninstalled() {
    disconnectEventStream()
}

// ---------------- Program selection/options ----------------

void setCurrentProgram() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) parent.setSelectedProgram(device, programToSelect.key)
    }
}

void setCurrentProgramOptions() {
    List<String> availableOptionList = getAvailableOptionsList()
    if (!availableOptionList) return

    for (int i = 0; i < availableOptionList.size(); ++i) {
        String optionTitle = availableOptionList[i]
        String optionName  = optionTitle.replaceAll("\\s","")

        // Read as Boolean safely (avoid legacy bool() issue)
        Boolean optionValue = (settings?."${optionName}" ?: false)

        def programOption = state?.foundAvailableProgramOptions?.find { it.name == optionTitle }
        if (programOption) {
            parent.setSelectedProgramOption(device, programOption.key, optionValue)
        } else {
            Utils.toLogger("debug", "Option not found for '${optionTitle}'")
        }
    }
}

void updateAvailableProgramList() {
    state.foundAvailablePrograms = parent.getAvailableProgramList(device)
    Utils.toLogger("debug", "updateAvailableProgramList state.foundAvailablePrograms: ${state.foundAvailablePrograms}")
    def programList = state.foundAvailablePrograms.collect { it.name }
    Utils.toLogger("debug", "getAvailablePrograms programList: ${programList}")
    sendEvent(name:"AvailableProgramsList", value: new groovy.json.JsonBuilder(programList).toString(), displayed: false)
}

void updateAvailableOptionsList() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            state.foundAvailableProgramOptions = parent.getAvailableProgramOptionsList(device, programToSelect.key)
            def programOptionsList = state.foundAvailableProgramOptions.collect { it.name }
            sendEvent(name:"AvailableOptionsList", value: new groovy.json.JsonBuilder(programOptionsList).toString(), displayed: false)
            Utils.toLogger("debug", "updateAvailableOptionList programOptionsList: ${programOptionsList}")
            return
        }
    }
    state.foundAvailableProgramOptions = []
    sendEvent(name:"AvailableOptionsList", value: [], displayed: false)
}

// ---------------- Helpers ----------------

void reset() {
    Utils.toLogger("debug", "reset")
    unschedule()
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    disconnectEventStream()
}

List<String> getAvailableProgramsList() {
    String json = device?.currentValue("AvailableProgramsList")
    if (json != null) return parseJson(json)
    return []
}

List<String> getAvailableOptionsList() {
    String json = device?.currentValue("AvailableOptionsList")
    if (json != null) return parseJson(json)
    return []
}

def on()  { safeSetPowerState(true)  }
def off() { safeSetPowerState(false) }

private void safeSetPowerState(Boolean val) {
    if (parent.respondsTo('setPowerState')) {
        parent.setPowerState(device, val)
    } else if (parent.respondsTo('setPowertate')) { // legacy typo
        parent.setPowertate(device, val)
    } else {
        Utils.toLogger("error", "Parent has no setPowerState/setPowertate method")
    }
}

// ---------------- Init & Event stream ----------------

void intializeStatus() {
    Utils.toLogger("debug", "Initializing the status of the device")

    updateAvailableProgramList()
    updateAvailableOptionsList()
    parent.intializeStatus(device)

    try {
        disconnectEventStream()
        connectEventStream()
    } catch (Exception e) {
        Utils.toLogger("error", "intializeStatus() failed: ${e.message}")
        setEventStreamStatusToDisconnected()
    }
}

void connectEventStream() {
    Utils.toLogger("debug", "connectEventStream()")
    parent.getHomeConnectAPI().connectDeviceEvents(device.deviceNetworkId, interfaces)
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
    Utils.toLogger("debug", "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)")
    if (device.currentValue("EventStreamStatus") == "connected" && notIfAlreadyConnected) {
        Utils.toLogger("debug", "already connected; skipping reconnection")
    } else {
        connectEventStream()
    }
}

void disconnectEventStream() {
    Utils.toLogger("debug", "disconnectEventStream()")
    parent.getHomeConnectAPI().disconnectDeviceEvents(device.deviceNetworkId, interfaces)
}

void setEventStreamStatusToConnected() {
    Utils.toLogger("debug", "setEventStreamStatusToConnected()")
    unschedule("setEventStreamStatusToDisconnected")
    if (device.currentValue("EventStreamStatus") == "disconnected") {
        sendEvent(name: "EventStreamStatus", value: "connected", displayed: true, isStateChange: true)
    }
    state.connectionRetryTime = 15
}

void setEventStreamStatusToDisconnected() {
    Utils.toLogger("debug", "setEventStreamStatusToDisconnected()")
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    if (state.connectionRetryTime) {
        state.connectionRetryTime *= 2
        if (state.connectionRetryTime > 900) state.connectionRetryTime = 900 // cap at 15 min
    } else {
        state.connectionRetryTime = 15
    }
    Utils.toLogger("debug", "reconnecting EventStream in ${state.connectionRetryTime} seconds")
    runIn(state.connectionRetryTime, "reconnectEventStream")
}

void eventStreamStatus(String text) {
    Utils.toLogger("debug", "Received eventstream status message: ${text}")
    def (String type, String message) = text.split(':', 2)
    switch (type) {
        case 'START':
            atomicState.oStartTokenExpires = now() + 60_000 // 60 seconds
            setEventStreamStatusToConnected()
            break
        case 'STOP':
            if (now() >= atomicState.oStartTokenExpires) {
                Utils.toLogger("debug", "eventStreamDisconnectGracePeriod: ${eventStreamDisconnectGracePeriod}")
                runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            } else {
                Utils.toLogger("debug", "stream started recently so ignore STOP event")
            }
            break
        default:
            Utils.toLogger("error", "Received unhandled Event Stream status message: ${text}")
            atomicState.oStartTokenExpires = now()
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            break
    }
}

// ---------------- Parsing ----------------

void parse(String text) {
    Utils.toLogger("debug", "Received eventstream message: ${text}")

    // Lightweight local STATUS parsing to keep key attributes/snippets up-to-date quickly
    try {
        if (text?.startsWith('data:')) {
            String payload = text.substring(5).trim()
            if (payload && payload.startsWith('{')) {
                def obj = new groovy.json.JsonSlurper().parseText(payload)
                def items = (obj?.items instanceof List) ? obj.items : []

                items.each { item ->
                    def key = item?.key as String
                    def rawVal = item?.value
                    String val = (rawVal instanceof String) ? rawVal : (rawVal?.toString())

                    switch (key) {
                        case 'BSH.Common.Setting.PowerState':
                        case 'BSH.Common.Status.PowerState':
                            String pwr = val?.tokenize('.')?.last()
                            if (pwr) {
                                sendEvent(name: "PowerState", value: pwr, isStateChange: true)
                                // Map to Hubitat switch capability
                                if (pwr == "On")  sendEvent(name: "switch", value: "on",  isStateChange: true)
                                if (pwr == "Off") sendEvent(name: "switch", value: "off", isStateChange: true)
                            }
                            break

                        case 'BSH.Common.Status.OperationState':
                            String op = val?.tokenize('.')?.last()
                            if (op) sendEvent(name: "OperationState", value: op, isStateChange: true)
                            break

                        // Add further quick mappings if you later need them
                    }
                }
            }
        }
    } catch (e) {
        Utils.toLogger("error", "STATUS payload parse error: ${e}")
    }

    // Always pass through to parent for full processing
    parent.processMessage(device, text)
    sendEvent(name: "DriverVersion", value: driverVer())
}

// ---------------- Logging helper ----------------

def deviceLog(level, msg) {
    Utils.toLogger(level, msg)
}

def Utils_create() {
    def instance = [:]

    instance.toLogger = { level, msg ->
        if (level && msg) {
            Integer levelIdx = LOG_LEVELS.indexOf(level)
            Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
            if (setLevelIdx < 0) setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
            if (levelIdx <= setLevelIdx) {
                log."${level}" "${device.displayName} ${msg}"
            }
        }
    }
    return instance
}
