//
//  AudioUtils.swift
//  CallLinq
//
//  Created by Keith Black on 11/17/19.
//  Copyright © 2019 CrowdMarket. All rights reserved.
//

import Foundation
import MediaPlayer

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
