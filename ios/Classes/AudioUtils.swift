//
//  AudioUtils.swift
//  CallLinq
//
//  Created by Keith Black on 11/17/19.
//  Copyright © 2019 CrowdMarket. All rights reserved.
//

import Foundation
import MediaPlayer

enum CMAudioDeviceType: String {
    case blueTooth      = "bluetooth";
    case wiredHeadset   = "wired_headset";
    case earpiece       = "earpiece";
    case speaker        = "speaker";
}

struct CMAudioDevice {
    init(id: String?=nil, name: String?=nil, type: CMAudioDeviceType?=nil, selected: Bool?=false){
        
        self.id = id
        self.name = name
        self.type = type
        self.selected = selected
    }
    
    var id: String?
    var name: String?
    var type: CMAudioDeviceType?
    var selected: Bool?
}

class CMAudioUtils {
    
    public static func isBluetoothsAudioInputAvailable() -> Bool {
        print("*** availableInputs ***")
        if let arrayInputs = AVAudioSession.sharedInstance().availableInputs {
            for input in arrayInputs {
                print(input)
                if input.portType == AVAudioSession.Port.bluetoothHFP
                {
                    print("============= Yo, I found a bluethooth audio device!")
                    return true
                }
            }
        }
        return false
    }
    
    public static func isBluetoothsAudioOutputAvailable() -> Bool {
        print("*** availableOutputs ***")
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        for output in outputs{
            print(output)
            if output.portType == AVAudioSession.Port.bluetoothA2DP || output.portType == AVAudioSession.Port.bluetoothHFP || output.portType == AVAudioSession.Port.bluetoothLE {
            return true
          }
        }
        return false
    }

    public static func isSpeakerOn() -> Bool {
        let session = AVAudioSession.sharedInstance()
        for port in session.currentRoute.outputs {
            if port.portType == AVAudioSession.Port.builtInSpeaker {
                return true
            }
        }
        return false
    }
    
    public static func toggleSpeaker(on: Bool) {
        let session = AVAudioSession.sharedInstance()
        if on {
            do {
                try session.overrideOutputAudioPort(.speaker)
                print("Speaker turned ON")
            } catch {
                print("Setting calling overrideOutputAudioPort to turn ON speaker. error = \(error.localizedDescription)")
            }
        } else {
            do {
                try session.overrideOutputAudioPort(.none)
                print("Speaker turned OFF")
            } catch {
                print("Setting calling overrideOutputAudioPort to turn OFF speaker. error = \(error.localizedDescription)")
            }
        }
    }
    
    public static func audioDevices() ->  [CMAudioDevice]? {
        let session = AVAudioSession.sharedInstance()
        var devices = [CMAudioDevice]()
        let currentDeviceUid = session.currentRoute.inputs.first?.uid
        
        // Add default types.
        devices.append(CMAudioDevice(id: "iPhone", name: "iPhone", type: CMAudioDeviceType.earpiece))
        devices.append(CMAudioDevice(id: "Speaker", name: "Speaker", type: CMAudioDeviceType.speaker, selected: isSpeakerOn()))
        
        if let arrayInputs = session.availableInputs {
            for input in arrayInputs {
                switch input.portType {
                    case .bluetoothHFP:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.blueTooth, selected: input.uid == currentDeviceUid))
                        print("============= Yo, I found a bluethooth audio device - \(input.portName)!")
                    case .carAudio:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.blueTooth, selected: input.uid == currentDeviceUid))
                        print("============= Yo, I found a CAR audio device - \(input.portName)!")
                    case .headsetMic:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.wiredHeadset, selected: input.uid == currentDeviceUid))
                        print("============= Yo, I found a headset - \(input.portName)!")
                    default:
                        break;
                }
            }
        }
        print("Available Devices:")
        for device in devices {
            print(device)
        }
        return devices
    }

    public static func audioDevicesToJSON(audioDevices: [CMAudioDevice]?) -> [[String: Any]] {

        var audioDevicesList = [[String:Any]]();
        
        if let devices = audioDevices {
            for device in devices {
                var deviceJSON = [String:Any]();
                if let value = device.name { deviceJSON.updateValue(value, forKey: "name") }
                if let value = device.selected { deviceJSON.updateValue(value, forKey: "selected") }
                if let value = device.type?.rawValue { deviceJSON.updateValue(value, forKey: "type") }
                if let value = device.id { deviceJSON.updateValue(value, forKey: "id") }
                audioDevicesList.append(deviceJSON)
            }
        }
        return audioDevicesList;
    }

    public static func printAudioChangeReason(reason: UInt) {
        
        print("=======================================================")
        switch (reason) {
        case AVAudioSession.RouteChangeReason.noSuitableRouteForCategory.rawValue:
            print("] Audio Route: The route changed because no suitable route is now available for the specified category.");
        case AVAudioSession.RouteChangeReason.wakeFromSleep.rawValue:
            print("] Audio Route: The route changed when the device woke up from sleep.");
        case AVAudioSession.RouteChangeReason.override.rawValue:
            print("] Audio Route: The output route was overridden by the app.");
        case AVAudioSession.RouteChangeReason.categoryChange.rawValue:
            print("] Audio Route: The category of the session object changed.");
        case AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue:
            print("] Audio Route: The previous audio output path is no longer available.");
        case AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue:
            print("] Audio Route: A preferred new audio output path is now available.");
        case AVAudioSession.RouteChangeReason.routeConfigurationChange.rawValue:
            print("] Audio Route: Route configuration change.");
        case AVAudioSession.RouteChangeReason.unknown.rawValue:
            print("] Audio Route: The reason for the change is unknown.");
        default:
            print("] Audio Route: The reason for the change is very unknown.");
        }
        let session = AVAudioSession.sharedInstance()
        print("current port: \(session.currentRoute)")
        print("=======================================================")
    }
    
}
