//
//  AudioUtils.swift
//  CallLinq
//
//  Created by Keith Black on 11/17/19.
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
    init(id: String?=nil, name: String?=nil, type: CMAudioDeviceType?=nil, selected: Bool?=false, portDescription: AVAudioSessionPortDescription?=nil){
        
        self.id = id
        self.name = name
        self.type = type
        self.selected = selected
    }
    
    var id: String?
    var name: String?
    var type: CMAudioDeviceType?
    var selected: Bool?
    var portDescription: AVAudioSessionPortDescription?
}

class CMAudioUtils {
    
    public static func isBluetoothsAudioInputAvailable() -> Bool {
        if let arrayInputs = AVAudioSession.sharedInstance().availableInputs {
            for input in arrayInputs {
                if input.portType == AVAudioSession.Port.bluetoothHFP
                {
                    return true
                }
            }
        }
        return false
    }
    
    public static func isBluetoothsAudioOutputAvailable() -> Bool {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        for output in outputs{
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

    public static func isEarpieceOn() -> Bool {
        let session = AVAudioSession.sharedInstance()
        for port in session.currentRoute.outputs {
            if port.portType == AVAudioSession.Port.builtInReceiver {
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
            } catch {
                NSLog("Failed to turn speaker on: %@", error.localizedDescription)
            }
        } else {
            do {
                try session.overrideOutputAudioPort(.none)
            } catch {
                NSLog("Failed to turn speaker off: %@", error.localizedDescription)
            }
        }
    }
    
    public static func selectAudioDevice(deviceID: String) {
        let session = AVAudioSession.sharedInstance()
        let audioDevices = CMAudioUtils.audioDevices()
        
        if let device = audioDevices?.first(where: {$0.id == deviceID}) {
            // If id == iPhone then we set to the earpiece
            if device.id == "iPhone" {
                do {
                    try session.setOutputDataSource(nil)
                } catch {
                    NSLog("Failed to route audio to earpiece")
                }
                return
            } else if device.id == "Speaker" {
                toggleSpeaker(on: true)
                return
            } else {
                if isSpeakerOn() {
                    toggleSpeaker(on: false)
                }
                do {
                    try session.setPreferredInput(device.portDescription)
                } catch {
                    NSLog("Failed to route audio to %@", String(describing: device.name))
                }
                
            }
        }
    }
    
    public static func audioDevices() ->  [CMAudioDevice]? {
        let session = AVAudioSession.sharedInstance()
        var devices = [CMAudioDevice]()
        let currentDeviceUid = session.currentRoute.inputs.first?.uid
        
        // TODO: I can't find a way to manually switch to the Earpiece so for now I am removing
        //       that option from the list of devices.
        // NOTE: If we only have speaker and earpiece then the app will show a toggle and
        //       not a selection list, but for now if we have Bluetooth as an option there
        //       is no way for the user to switch back to earpiece.
        //
        // Add default types.
        // devices.append(CMAudioDevice(id: "iPhone", name: "iPhone", type: CMAudioDeviceType.earpiece, selected: isEarpieceOn()))
        devices.append(CMAudioDevice(id: "Speaker", name: "Speaker", type: CMAudioDeviceType.speaker, selected: isSpeakerOn()))
        
        if let arrayInputs = session.availableInputs {
            for input in arrayInputs {
                switch input.portType {
                    case .bluetoothHFP:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.blueTooth, selected: input.uid == currentDeviceUid, portDescription: input))
                    case .carAudio:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.blueTooth, selected: input.uid == currentDeviceUid, portDescription: input))
                    case .headsetMic:
                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.wiredHeadset, selected: input.uid == currentDeviceUid, portDescription: input))
//                    case .builtInMic:
//                        devices.append(CMAudioDevice(id: input.uid, name: input.portName, type: CMAudioDeviceType.earpiece, selected: input.uid == currentDeviceUid, portDescription: input))
//                        print("============= Yo, I found a headset - \(input.portName)!")
                    default:
                        break;
                }
            }
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

}
