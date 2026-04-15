import Flutter
import UIKit
import AVFoundation
import PushKit
import TwilioVoice
import CallKit

enum CallState: String {
    case ringing                = "ringing"
    case connected              = "connected"
    case reconnecting           = "reconnecting"
    case reconnected            = "reconnected"
    case call_invite            = "call_invite"
    case call_invite_canceled   = "call_invite_canceled"
    case call_reject            = "call_reject"
    case connectFailed          = "connect_failed"
    case call_ended             = "call_ended"
    case unhold                 = "unhold"
    case hold                   = "hold"
    case unmute                 = "unmute"
    case mute                   = "mute"
    case speaker_on             = "speaker_on"
    case speaker_off            = "speaker_off"
    case audio_route_change     = "audio_route_change"
    case call_quality_warning   = "call_quality_warning"
}
enum CallDirection: String {
    case incoming = "incoming"
    case outgoing = "outgoing"
}

public class SwiftFlutterTwilioVoicePlugin: NSObject, FlutterPlugin,  FlutterStreamHandler, PKPushRegistryDelegate, NotificationDelegate, CallDelegate, AVAudioPlayerDelegate, CXProviderDelegate {

    var _result: FlutterResult?
    private var eventSink: FlutterEventSink?

    //var baseURLString = ˝""
    // If your token server is written in PHP, accessTokenEndpoint needs .php extension at the end. For example : /accessToken.php
    //var accessTokenEndpoint = "/accessToken"
    var accessToken:String?
    var identity = "alice"
    var callTo: String = "error"
    var deviceTokenString: Data?
    var callArgs: Dictionary<String, AnyObject> = [String: AnyObject]()

    var voipRegistry: PKPushRegistry
    var incomingPushCompletionCallback: (()->Swift.Void?)? = nil

   var inviteParamCallerId:String?
   var callKitCompletionCallback: ((Bool)->Swift.Void?)? = nil
   var audioDevice: DefaultAudioDevice = DefaultAudioDevice()

    // activeCall represents the last connected call
    var activeCall:Call?
    var activeCallInvites: [String: CallInvite]! = [:]
    var activeCalls: [String: Call]! = [:]

    var lastCallInvite:CallInvite?

   var callKitProvider: CXProvider?
   var callKitCallController: CXCallController
   var userInitiatedDisconnect: Bool = false
   var callOutgoing: Bool = false
    
    static var appName: String {
        get {
            return (Bundle.main.infoDictionary!["CFBundleDisplayName"] as? String) ?? "Define CFBundleDisplayName"
        }
    }

    public override init() {

        voipRegistry = PKPushRegistry.init(queue: DispatchQueue.main)
        let configuration = CXProviderConfiguration(localizedName: SwiftFlutterTwilioVoicePlugin.appName)
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        if let callKitIcon = UIImage(named: "CallKitAppButtonLogo") {
            configuration.iconTemplateImageData = callKitIcon.pngData()
        }

        callKitProvider = CXProvider(configuration: configuration)
        callKitCallController = CXCallController()

        //super.init(coder: aDecoder)
        super.init()
        
        initAudioDevice();

        callKitProvider?.setDelegate(self, queue: nil)

        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = Set([PKPushType.voIP])

        // This Observer listens for state changes in the mic/speaker
        NotificationCenter.default.addObserver(
                self, selector:#selector(SwiftFlutterTwilioVoicePlugin.audioRouteChanged(_:)),
                name:AVAudioSession.routeChangeNotification, object:nil)

         let appDelegate = UIApplication.shared.delegate
         guard let controller = appDelegate?.window??.rootViewController as? FlutterViewController else {
         fatalError("rootViewController is not type FlutterViewController")
         }
        if let registrar = controller.registrar(forPlugin: "flutter_twilio_voice") {
            let eventChannel = FlutterEventChannel(name: "flutter_twilio_voice/events", binaryMessenger: registrar.messenger())
            eventChannel.setStreamHandler(self)
        }
        CMContacts.cmContacts.loadCache(){}
    }


      deinit {
          // CallKit has an odd API contract where the developer must call invalidate or the CXProvider is leaked.
          callKitProvider?.invalidate()
      }


    var audioDeviceInitialized = false
    func initAudioDevice() {
        /*
         * The important thing to remember when providing a AudioDevice is that the device must be set
         * before performing any other actions with the SDK (such as connecting a Call, or accepting an incoming Call).
         * This function will make sure we do not initialize the audioDevice more than once.
         */
        if !audioDeviceInitialized {
            audioDeviceInitialized = true
            TwilioVoiceSDK.audioDevice = audioDevice
        }
    }

  public static func register(with registrar: FlutterPluginRegistrar) {

    let instance = SwiftFlutterTwilioVoicePlugin()
    let methodChannel = FlutterMethodChannel(name: "flutter_twilio_voice/messages", binaryMessenger: registrar.messenger())
    let eventChannel = FlutterEventChannel(name: "flutter_twilio_voice/events", binaryMessenger: registrar.messenger())
    eventChannel.setStreamHandler(instance)
    registrar.addMethodCallDelegate(instance, channel: methodChannel)

  }

  public func handle(_ flutterCall: FlutterMethodCall, result: @escaping FlutterResult) {
    _result = result

    let arguments:Dictionary<String, AnyObject> = flutterCall.arguments as! Dictionary<String, AnyObject>;

    if flutterCall.method == "tokens" {
        guard let token = arguments["accessToken"] as? String else {return}
        self.accessToken = token
        if let deviceToken = deviceTokenString, let token = accessToken {
//            initAudioDevice();
            dLog("pushRegistry:attempting to register with twilio")
            TwilioVoiceSDK.register(accessToken: token, deviceToken: deviceToken) { (error) in
                if let error = error {
                    dLog("An error occurred while registering: \(error.localizedDescription)")
                }
                else {
                    dLog("Successfully registered for VoIP push notifications.")
                }
            }
        }
    } else if flutterCall.method == "makeCall" {
        guard let callTo = arguments["to"] as? String else {return}
        guard let callFrom = arguments["from"] as? String else {return}
        let callToDisplayName:String = arguments["toDisplayName"] as? String ?? callTo
        self.callArgs = arguments
        self.callOutgoing = true
        self.callTo = callTo
        self.identity = callFrom
        makeCall(to: callTo, displayName: callToDisplayName)
    }
    else if flutterCall.method == "muteCall"
    {
        if (self.activeCall != nil) {
           let muted = self.activeCall!.isMuted
           self.activeCall!.isMuted = !muted
           sendPhoneCallEvents(json: ["event": !muted ? CallState.mute.rawValue : CallState.unmute.rawValue])
        }
    }
    else if flutterCall.method == "toggleSpeaker"
    {
        guard let speakerIsOn = arguments["speakerIsOn"] as? Bool else {return}
        CMAudioUtils.toggleSpeaker(on: speakerIsOn)
//        sendPhoneCallEvents(json: ["event": speakerIsOn ? CallState.speaker_on.rawValue : CallState.speaker_off.rawValue])
    }
    else if flutterCall.method == "selectAudioDevice"
    {
        guard let deviceID = arguments["deviceID"] as? String else {return}
        CMAudioUtils.selectAudioDevice(deviceID: deviceID)
    }
    else if flutterCall.method == "isOnCall"
        {
            result(connected);
            return;
        }
    else if flutterCall.method == "sendDigits"
    {
        guard let digits = arguments["digits"] as? String else {return}
        if (self.activeCall != nil) {
            self.activeCall!.sendDigits(digits);
        }
    }
    /* else if flutterCall.method == "receiveCalls"
    {
        guard let clientIdentity = arguments["clientIdentifier"] as? String else {return}
        self.identity = clientIdentity;
    } */
    else if flutterCall.method == "holdCall" {
        if (self.activeCall != nil) {

            let hold = self.activeCall!.isOnHold
            self.activeCall!.isOnHold = !hold
           sendPhoneCallEvents(json: ["event": !hold ? CallState.hold.rawValue : CallState.unhold.rawValue])
        }
    }
    else if flutterCall.method == "answer" {
        // nuthin
    }
    else if flutterCall.method == "reject" {
        // handled by CallKit.
    }
    else if flutterCall.method == "unregister" {
        self.unregister()
    }
    else if flutterCall.method == "hangUp"
    {
        if (connected) {
            dLog("hangUp method invoked")
            self.userInitiatedDisconnect = true
            if let uuid = self.activeCall?.uuid {
                performEndCallAction(uuid: uuid)
            }
        }
    }
    else if flutterCall.method == "replayCallConnection"
    {
        // This method was created for situations where the Flutter App does not
        // receive the inital CallInvite. This can occur if the app is not
        // running or in the background and CallKit or and Android Notification
        // answers the call.
        //
        // When the replayed callInvite is sent an extra parameter will be set 'replay: true'
        if let ci = lastCallInvite, let call = self.activeCall, call.state == Call.State.connected {
            dLog("Replay CallInvite invoked")
            buildAndSendInviteEvent(ci: ci, replay: true)
            sendCallDidConnectEvent(call: call)
        }
    }    else if flutterCall.method == "refreshAudioRoute" {
        queryAndSendAudioDeviceInfo()
    }
    result(true)
  }

    var connected : Bool {
        get {
//            dLog("*** TwilioTrace get connected: call = \(String(describing: call)), call.state = \(String(describing: call?.state.rawValue)) ")
            if let call = self.activeCall, call.state == Call.State.connected {
                return true
            }
            return false
        }
    }
    
  func makeCall(to: String, displayName: String)
  {
        if (connected) {
            self.userInitiatedDisconnect = true
            if let uuid = self.activeCall?.uuid {
                performEndCallAction(uuid: uuid)
            }
        } else {
            initAudioDevice();

            let uuid = UUID()
            let handle = displayName

            self.checkRecordPermission { (permissionGranted) in
                if (!permissionGranted) {
                    let alertController: UIAlertController = UIAlertController(title: SwiftFlutterTwilioVoicePlugin.appName + " Permission",
                                                                               message: "Microphone permission not granted",
                                                                               preferredStyle: .alert)

                    let continueWithMic: UIAlertAction = UIAlertAction(title: "Continue without microphone",
                                                                       style: .default,
                                                                       handler: { (action) in
                        self.performStartCallAction(uuid: uuid, handle: handle)
                    })
                    alertController.addAction(continueWithMic)

                    let goToSettings: UIAlertAction = UIAlertAction(title: "Settings",
                                                                    style: .default,
                                                                    handler: { (action) in
                        UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!,
                                                  options: [UIApplication.OpenExternalURLOptionsKey.universalLinksOnly: false],
                                                  completionHandler: nil)
                    })
                    alertController.addAction(goToSettings)

                    let cancel: UIAlertAction = UIAlertAction(title: "Cancel",
                                                              style: .cancel,
                                                              handler: { (action) in
                        //self.toggleUIState(isEnabled: true, showCallControl: false)
                        //self.stopSpin()
                    })
                    alertController.addAction(cancel)
                    guard let currentViewController = UIApplication.shared.keyWindow?.topMostViewController() else {
                        return
                    }
                    currentViewController.present(alertController, animated: true, completion: nil)

                } else {
                    self.performStartCallAction(uuid: uuid, handle: handle)
                }
            }
        }
  }

    func queryAndSendAudioDeviceInfo() -> Void {
        if let audioDevices = CMAudioUtils.audioDevices(){
        
        self.sendPhoneCallEvents(json: ["event": CallState.audio_route_change.rawValue,
            "bluetooth_available": CMAudioUtils.isBluetoothsAudioInputAvailable(),
            "speaker_on": CMAudioUtils.isSpeakerOn(),
            "devices" : CMAudioUtils.audioDevicesToJSON(audioDevices:audioDevices)
        ])}
    }
    
    /* func fetchAccessToken() -> String? {
        let endpointWithIdentity = String(format: "%@?identity=%@", accessTokenEndpoint, identity)
        guard let accessTokenURL = URL(string: baseURLString + endpointWithIdentity) else {
            return nil
        }

        return try? String.init(contentsOf: accessTokenURL, encoding: .utf8)
    } */

    func checkRecordPermission(completion: @escaping (_ permissionGranted: Bool) -> Void) {
        switch AVAudioSession.sharedInstance().recordPermission {
            case AVAudioSession.RecordPermission.granted:
            // Record permission already granted.
            completion(true)
            break
            case AVAudioSession.RecordPermission.denied:
            // Record permission denied.
            completion(false)
            break
            case AVAudioSession.RecordPermission.undetermined:
            // Requesting record permission.
            // Optional: pop up app dialog to let the users know if they want to request.
            AVAudioSession.sharedInstance().requestRecordPermission({ (granted) in
                completion(granted)
            })
            break
        default:
            completion(false)
            break
        }
    }


  // MARK: PKPushRegistryDelegate
      public func pushRegistry(_ registry: PKPushRegistry, didUpdate credentials: PKPushCredentials, for type: PKPushType) {
          dLog("pushRegistry:didUpdatePushCredentials:forType:")

          if (type != .voIP) {
              return
          }

          //guard let accessToken = fetchAccessToken() else {
          //    return
          //}

          let deviceToken = credentials.token

          dLog("pushRegistry:attempting to register with twilio")
        if let token = accessToken {
            TwilioVoiceSDK.register(accessToken: token, deviceToken: deviceToken) { (error) in
                if let error = error {
                    dLog("An error occurred while registering: \(error.localizedDescription)")
                }
                else {
                    dLog("Successfully registered for VoIP push notifications.")
                }
            }
        }
          self.deviceTokenString = deviceToken
      }

      public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
          dLog("pushRegistry:didInvalidatePushTokenForType:")

          if (type != .voIP) {
              return
          }

          self.unregister()
      }

      func unregister() {

          guard let deviceToken = deviceTokenString, let token = accessToken else {
              return
          }

        TwilioVoiceSDK.unregister(accessToken: token, deviceToken: deviceToken) { (error) in
              if let error = error {
                  dLog("An error occurred while unregistering: \(error.localizedDescription)")
              } else {
                  dLog("Successfully unregistered from VoIP push notifications.")
              }
          }

          self.deviceTokenString = nil
      }

    /**
         * Try using the `pushRegistry:didReceiveIncomingPushWithPayload:forType:withCompletionHandler:` method if
         * your application is targeting iOS 11. According to the docs, this delegate method is deprecated by Apple.
         */
        public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType) {
            dLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:")

            if (type == PKPushType.voIP) {
                TwilioVoiceSDK.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: nil)
            }
        }

        /**
         * This delegate method is available on iOS 11 and above. Call the completion handler once the
         * notification payload is passed to the `TwilioVoiceSDK.handleNotification()` method.
         */
        public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
            dLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:completion:")
            // Save for later when the notification is properly handled.
            self.incomingPushCompletionCallback = completion

            if (type == PKPushType.voIP) {
                TwilioVoiceSDK.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: nil)
            }

            if let version = Float(UIDevice.current.systemVersion), version < 13.0 {
                // Save for later when the notification is properly handled.
                self.incomingPushCompletionCallback = completion
            } else {
                /**
                * The Voice SDK processes the call notification and returns the call invite synchronously. Report the incoming call to
                * CallKit and fulfill the completion before exiting this callback method.
                */
                completion()
            }
        }

        func incomingPushHandled() {
            if let completion = self.incomingPushCompletionCallback {
                completion()
                self.incomingPushCompletionCallback = nil
            }
        }

        // MARK: NotificaitonDelegate
    public func callInviteReceived(callInvite ci: CallInvite) {
        dLog("callInviteReceived:")
        
        // Twilio allows sending custom parameters. The code below checks to see if there is a
        // parameter called callerId, if so we'll use that number. This allows us to show the
        // actual from callerId rather than the 'client:ID' notation that may be present when
        // calling from a server/mobile device.
        // If this is not provided we'll use the standard callInvite.from.
        var number:String?
        var name:String?
        
        if let parameters = ci.customParameters {
            number = parameters["callerId"]
            name = parameters["lynkName"]
            inviteParamCallerId = number ?? ci.from
            if let nam = name {
                inviteParamCallerId? += ":\(nam)"
            }
        }
                
        initAudioDevice();
        let from:String = inviteParamCallerId ?? "Voice Bot"
        
        reportIncomingCall(from: from, uuid: ci.uuid)
        
        activeCallInvites[ci.uuid.uuidString] = ci
        lastCallInvite = ci

        updateCallerIdFromContacts(uuid: ci.uuid, number: number, name: name)
                
        buildAndSendInviteEvent(ci: ci)
    }
    
    private func buildAndSendInviteEvent(ci: CallInvite, replay:Bool=false) {

        var inviteInfo:[String : Any] = ["event": CallState.call_invite.rawValue, "from": inviteParamCallerId ?? "Voice Bot", "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid, "pluginDisplayedAnswerScreen": true];
        if let parameters = ci.customParameters {
            inviteInfo.updateValue(parameters, forKey: "customParameters")
        }
        if replay {
            inviteInfo.updateValue(true, forKey: "replay")
        }
        sendPhoneCallEvents(json: inviteInfo)
    }

    private func sendCallDidConnectEvent(call: Call) {
        var callInfo = callToJson(call: call);
        callInfo.updateValue(CallState.connected.rawValue, forKey: "event")
        sendPhoneCallEvents(json: callInfo)
    }
    
    private func updateCallerIdFromContacts(uuid: UUID, number:String? , name:String? ) {
        guard let num = number else {
            return
        }
        CMContacts.cmContacts.getContactName(phoneNumber: num, returnImage:false, completionHandler: { contactName, image in
            if contactName != nil {
                var callerId = contactName ?? num;
                if name != nil {
                    callerId += ":\(name!)"
                }
                self.updateCallCallerId(uuid: uuid, from: callerId)
            }
        })
    }
    
    public func cancelledCallInviteReceived(cancelledCallInvite: CancelledCallInvite, error: Error) {
        dLog("cancelledCallInviteCanceled:error:, error: \(error.localizedDescription)")
        
        guard let activeCallInvites = activeCallInvites, !activeCallInvites.isEmpty else {
            dLog("No pending call invite")
            return
        }

        let callInvite = activeCallInvites.values.first { invite in invite.callSid == cancelledCallInvite.callSid }

        if let ci = callInvite {
            performEndCallAction(uuid: ci.uuid)
            self.activeCallInvites.removeValue(forKey: ci.uuid.uuidString)
            
            var inviteInfo:[String : Any] = ["event": CallState.call_invite_canceled.rawValue, "from": ci.from ?? "", "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid];
            if let parameters = ci.customParameters {
                inviteInfo.updateValue(parameters, forKey: "customParameters")
            }
            sendPhoneCallEvents(json: inviteInfo)
        }
    }

        // MARK: CallDelegate
    public func callDidStartRinging(call: Call) {
        var callInfo = callToJson(call: call);
        callInfo.updateValue(CallState.ringing.rawValue, forKey: "event")
        sendPhoneCallEvents(json: callInfo)
        }

    public func callDidConnect(call: Call) {
        sendCallDidConnectEvent(call: call)
        
        self.callKitCompletionCallback?(true)
        toggleAudioRoute(toSpeaker: false)
    }
    
    public func callIsReconnecting(call: Call, error: Error) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.reconnecting.rawValue, forKey: "event")
            callInfo.updateValue(error.localizedDescription, forKey: "error")
            sendPhoneCallEvents(json: callInfo)
            dLog("call:isReconnectingWithError:")
        }

    public func callDidReconnect(call: Call) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.reconnected.rawValue, forKey: "event")
            sendPhoneCallEvents(json: callInfo)
            dLog("callDidReconnect:")
        }

    public func callDidFailToConnect(call: Call, error: Error) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.connectFailed.rawValue, forKey: "event")
            callInfo.updateValue(error.localizedDescription, forKey: "error")
            sendPhoneCallEvents(json: callInfo)
            
            dLog("Call failed to connect: \(error.localizedDescription)")

            if let completion = self.callKitCompletionCallback {
                completion(false)
            }

            if let provider = callKitProvider {
                provider.reportCall(with: call.uuid!, endedAt: Date(), reason: CXCallEndedReason.failed)
            }

// TODO: See if it works without this code. The latest Twilio Example code does not do this 9/2/2021
//            if let uuid = self.activeCall?.uuid {
//                performEndCallAction(uuid: uuid)
//            }
            callDisconnected(call: call)
    }

    public func callDidDisconnect(call: Call, error: Error?) {
            dLog("didDisconnectWithError, error: \(String(describing: error?.localizedDescription))")
        
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.call_ended.rawValue, forKey: "event")
            if let error = error {
                callInfo.updateValue(error.localizedDescription, forKey: "error")
            }
            sendPhoneCallEvents(json: callInfo)

            if let error = error {
                self.sendErrorEvent(message: "Call Failed: \(error.localizedDescription)")
            }

            if !self.userInitiatedDisconnect {
                var reason = CXCallEndedReason.remoteEnded

                if error != nil {
                    reason = .failed
                }
                if let provider = callKitProvider, let uuid = call.uuid {
                    provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
                }
            }
        callDisconnected(call: call)
    }

    func callDisconnected(call: Call) {
        
        if call == activeCall {
            activeCall = nil
        }
        activeCalls.removeValue(forKey: call.uuid!.uuidString)

        self.callOutgoing = false
        self.userInitiatedDisconnect = false
    }

    func call(call: Call, didReceiveQualityWarnings currentWarnings: Set<NSNumber>, previousWarnings: Set<NSNumber>) {
        /**
        * currentWarnings: existing quality warnings that have not been cleared yet
        * previousWarnings: last set of warnings prior to receiving this callback
        *
        * Example:
        *   - currentWarnings: { A, B }
        *   - previousWarnings: { B, C }
        *   - intersection: { B }
        *
        * Newly raised warnings = currentWarnings - intersection = { A }
        * Newly cleared warnings = previousWarnings - intersection = { C }
        */
        
        dLog("Inside call:didReceiveQualityWarnings: current warnings: \(currentWarnings)")

        var warningsIntersection: Set<NSNumber> = currentWarnings
        warningsIntersection = warningsIntersection.intersection(previousWarnings)
        
        var newWarnings: Set<NSNumber> = currentWarnings
        newWarnings.subtract(warningsIntersection)
        
        if newWarnings.count > 0 {
            dLog("New Quality Warnings: \(newWarnings)")
        }
        
        var clearedWarnings: Set<NSNumber> = previousWarnings
        clearedWarnings.subtract(warningsIntersection)
        if clearedWarnings.count > 0 {
            dLog("Cleared Quality Warnings: \(clearedWarnings)")
        }
        sendPhoneCallWarningEvent(currentWarnings)
    }
    
    func sendPhoneCallWarningEvent(_ warnings: Set<NSNumber>) {
        var warningMessage: String = ""
        let mappedWarnings: [String] = warnings.map { number in warningString(Call.QualityWarning(rawValue: number.uintValue)!)}
        warningMessage += mappedWarnings.joined(separator: ", ")
        
        let warningInfo:[String : Any] = ["event": CallState.call_quality_warning.rawValue, "warning": warningMessage, "isCleared": warnings.isEmpty];
        sendPhoneCallEvents(json: warningInfo)
    }
    
    func warningString(_ warning: Call.QualityWarning) -> String {
        switch warning {
        case .highRtt: return "Round Trip Time"
        case .highJitter: return "Jitter"
        case .highPacketsLostFraction: return "Packet Loss"
        case .lowMos: return "Low Call Quality"
        case .constantAudioInputLevel: return "Audio Level"
        default: return "Unknown warning"
        }
    }
    
        // MARK: AVAudioSession
        func toggleAudioRoute(toSpeaker: Bool) {
            // The mode set by the Voice SDK is "VoiceChat" so the default audio route is the built-in receiver. Use port override to switch the route.
            audioDevice.block = {
                DefaultAudioDevice.DefaultAVAudioSessionConfigurationBlock()
                do {
                    if (toSpeaker) {
                        try AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
                    } else {
                        try AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
                    }
                } catch {
                    dLog(error.localizedDescription)
                }
            }
            audioDevice.block()
        }

    // MARK: CXProviderDelegate
        public func providerDidReset(_ provider: CXProvider) {
            dLog("providerDidReset:")
            audioDevice.isEnabled = false
        }

        public func providerDidBegin(_ provider: CXProvider) {
            dLog("providerDidBegin")
        }

        public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
            dLog("provider:didActivateAudioSession:")
            audioDevice.isEnabled = true
        }

        public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
            dLog("provider:didDeactivateAudioSession:")
            audioDevice.isEnabled = false
        }

        public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
            dLog("provider:timedOutPerformingAction:")
        }

        public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
            dLog("provider:performCXStartCallAction:")

            audioDevice.isEnabled = false
            audioDevice.block()

            provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())

            self.performVoiceCall(uuid: action.callUUID, client: "") { (success) in
                if (success) {
                    provider.reportOutgoingCall(with: action.callUUID, connectedAt: Date())
                    action.fulfill()
                } else {
                    action.fail()
                }
            }
        }

        public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
            dLog("provider:performAnswerCallAction:")

            audioDevice.isEnabled = false
            audioDevice.block()

            self.performAnswerVoiceCall(uuid: action.callUUID) { (success) in
                if (success) {
                    action.fulfill()
                } else {
                    action.fail()
                }
            }

            action.fulfill()
        }

        public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
            dLog("provider:perform action: CXEndCallAction:")
            
            if let ci = activeCallInvites[action.callUUID.uuidString] {
                if ci.uuid == activeCall?.uuid {
                    dLog("provider:CXEndCallAction on active call, sending call_reject to client")

                    var inviteInfo:[String : Any] = ["event": CallState.call_reject.rawValue, "from": ci.from ?? "", "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid];
                    if let parameters = ci.customParameters {
                        inviteInfo.updateValue(parameters, forKey: "customParameters")
                    }
                    sendPhoneCallEvents(json: inviteInfo)
                } else {
                    dLog("provider:CXEndCallAction from reject of incoming call, will not send notice to client.")
                }
                
                ci.reject()
                activeCallInvites.removeValue(forKey: action.callUUID.uuidString)
            } else if let call = activeCalls[action.callUUID.uuidString] {
                dLog("provider:CXEndCallAction: disconnecting call")
                call.disconnect()
            } else {
                dLog("Unknown UUID to perform end-call action with")
            }

            action.fulfill()

//            if (self.activeCall != nil) {
//                dLog("provider:CXEndCallAction: disconnecting call")
//                self.activeCall?.disconnect()
//                //self.callInvite = nil
//                //self.call = nil
//                action.fulfill()
//                return
//            }
//
//            if let ci = self.callInvite {
//                dLog("provider:CXEndCallAction: rejecting call")
//
//                var inviteInfo:[String : Any] = ["event": CallState.call_reject.rawValue, "from": ci.from ?? "", "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid];
//                if let parameters = ci.customParameters {
//                    inviteInfo.updateValue(parameters, forKey: "customParameters")
//                }
//                sendPhoneCallEvents(json: inviteInfo)
//
//                ci.reject()
//                //self.callInvite = nil
//                //self.call = nil
//                action.fulfill()
//                return
//            }
        }
    
    
    // TODO: remove this after debugging!!!
    public func provider(_ provider: CXProvider,
                  execute transaction: CXTransaction) -> Bool {
        
        print("CXTransaction.actions:")
        for action in transaction.actions {
            print(action)
        }
        return false;
    }

        public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
            dLog("provider:performSetHeldAction:")
            
            if let call = activeCalls[action.callUUID.uuidString] {
                call.isOnHold = action.isOnHold
                action.fulfill()
            } else {
                action.fail()
            }
        }

        public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
            dLog("provider:performSetMutedAction:")

            if let call = activeCalls[action.callUUID.uuidString] {
                call.isMuted = action.isMuted
                action.fulfill()
            } else {
                action.fail()
            }
        }

        // MARK: Call Kit Actions
        func performStartCallAction(uuid: UUID, handle: String) {
            guard let provider = callKitProvider else {
                dLog("CallKit provider not available")
                return
            }
            
            let callHandle = CXHandle(type: .generic, value: handle)
            let startCallAction = CXStartCallAction(call: uuid, handle: callHandle)
            let transaction = CXTransaction(action: startCallAction)

            callKitCallController.request(transaction)  { error in
                if let error = error {
                    dLog("StartCallAction transaction request failed: \(error.localizedDescription)")
                    return
                }

                dLog("StartCallAction transaction request successful")

                let callUpdate = CXCallUpdate()
                callUpdate.remoteHandle = callHandle
                callUpdate.supportsDTMF = true
                callUpdate.supportsHolding = true
                callUpdate.supportsGrouping = false
                callUpdate.supportsUngrouping = false
                callUpdate.hasVideo = false

                provider.reportCall(with: uuid, updated: callUpdate)
            }
        }

        func updateCallCallerId(uuid: UUID, from: String) {
            let callUpdate = CXCallUpdate()
            callUpdate.localizedCallerName = from
            self.callKitProvider?.reportCall(with: uuid, updated: callUpdate)
        }
        
        func reportIncomingCall(from: String, uuid: UUID) {
            guard let provider = callKitProvider else {
                dLog("CallKit provider not available")
                return
            }

            let callHandle = CXHandle(type: .generic, value: from)

            let callUpdate = CXCallUpdate()
            callUpdate.remoteHandle = callHandle
            callUpdate.supportsDTMF = true
            callUpdate.supportsHolding = true
            callUpdate.supportsGrouping = false
            callUpdate.supportsUngrouping = false
            callUpdate.hasVideo = false

            provider.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
                if let error = error {
                    dLog("Failed to report incoming call successfully: \(error.localizedDescription).")
                } else {
                    dLog("Incoming call successfully reported.")
                }
            }
        }

        func performEndCallAction(uuid: UUID) {

            dLog("performEndCallAction method invoked")

            let endCallAction = CXEndCallAction(call: uuid)
            let transaction = CXTransaction(action: endCallAction)

            callKitCallController.request(transaction) { error in
                if let error = error {
                    dLog("EndCallAction transaction request failed: \(error.localizedDescription).")
                } else {
                    dLog("EndCallAction transaction request successful")
                }
            }
        }

        func performVoiceCall(uuid: UUID, client: String?, completionHandler: @escaping (Bool) -> Swift.Void) {
            guard let token = accessToken else {
                completionHandler(false)
                return
            }

            let connectOptions: ConnectOptions = ConnectOptions(accessToken: token) { (builder) in
                builder.params = ["PhoneNumber": self.callTo, "From": self.identity]
                for (key, value) in self.callArgs {
                    if (key != "to" && key != "toDisplayName" && key != "from") {
                        builder.params[key] = "\(value)"
                    }
                }
                builder.uuid = uuid
            }
            dLog("connectOptions.uuid = \(String(describing: connectOptions.uuid))")
            dLog("passed uuid = \(String(describing: uuid))")

            let call = TwilioVoiceSDK.connect(options: connectOptions, delegate: self)
            self.activeCall = call
            self.activeCalls[call.uuid!.uuidString] = call
            self.callKitCompletionCallback = completionHandler
        }

        func performAnswerVoiceCall(uuid: UUID, completionHandler: @escaping (Bool) -> Swift.Void) {
            guard let callInvite = activeCallInvites[uuid.uuidString] else {
                dLog("No CallInvite matches the UUID")
                return
            }
        
            let acceptOptions: AcceptOptions = AcceptOptions(callInvite: callInvite) { (builder) in
                builder.uuid = callInvite.uuid
            }
            dLog("performAnswerVoiceCall: answering call")
            let call = callInvite.accept(options: acceptOptions, delegate: self)
            self.activeCall = call
            activeCalls[call.uuid!.uuidString] = call
            self.callKitCompletionCallback = completionHandler
            
            activeCallInvites.removeValue(forKey: uuid.uuidString)

            guard #available(iOS 13, *) else {
                self.incomingPushHandled()
                return
            }
        }

    public func onListen(withArguments arguments: Any?,
                         eventSink: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = eventSink

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(CallDelegate.callDidDisconnect),
            name: NSNotification.Name(rawValue: "PhoneCallEvent"),
            object: nil)

        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        NotificationCenter.default.removeObserver(self)
        eventSink = nil
        return nil
    }

    private func sendPhoneCallEvents(json: [String: Any]) {
        dLog("Call Event: \(json)")
        guard let eventSink = eventSink else {
            return
        }
        eventSink(json)
    }

        // MARK: AV Functions (microphone handling)

    var sendWarningCount = 0;
    @objc func audioRouteChanged(_ notification:Notification) {
                
        //--------------------------------------------
        // SEND TEST QUALITY WARNING MESSAGES
//        if let actCall = activeCall {
//            sendWarningCount+=1
//            if sendWarningCount % 2 == 0 {
//                call(call: actCall, didReceiveQualityWarnings: [0, 1, 2, 3, 4], previousWarnings: [])
//            } else {
//                call(call: actCall, didReceiveQualityWarnings: [], previousWarnings: [0, 1, 2])
//            }
//        }
        //--------------------------------------------

        if let userInfo = notification.userInfo {
            let reason = userInfo[AVAudioSessionRouteChangeReasonKey] as! UInt
            
            CMAudioUtils.printAudioChangeReason(reason: reason)
            
            switch (reason) {
            case AVAudioSession.RouteChangeReason.noSuitableRouteForCategory.rawValue,
                 AVAudioSession.RouteChangeReason.override.rawValue,
                 AVAudioSession.RouteChangeReason.categoryChange.rawValue,
                 AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue,
                 AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue:
                
                DispatchQueue.main.async(execute: { () -> Void in
                    self.queryAndSendAudioDeviceInfo()
                })
                break
            default:
                break
            }
        }
    }
    
    private func sendErrorEvent(message: String, details: String?=nil) {
        dLog(message)
        guard let eventSink = eventSink else {
            return
        }
        eventSink(FlutterError(code: "unavailable",
                                message: message,
                                details: details))
    }
    
    internal func callToJson(call: Call) -> [String : Any] {
        let direction = (self.callOutgoing ? CallDirection.outgoing.rawValue : CallDirection.incoming.rawValue)
        let from = (call.from ?? self.identity)
        let to = (call.to ?? self.callTo)
        return ["from": from, "to": to, "direction": direction, "sid": call.sid, "muted": call.isMuted, "onhold": call.isOnHold];
    }
}

extension UIWindow {
    func topMostViewController() -> UIViewController? {
        guard let rootViewController = self.rootViewController else {
            return nil
        }
        return topViewController(for: rootViewController)
    }

    func topViewController(for rootViewController: UIViewController?) -> UIViewController? {
        guard let rootViewController = rootViewController else {
            return nil
        }
        guard let presentedViewController = rootViewController.presentedViewController else {
            return rootViewController
        }
        switch presentedViewController {
        case is UINavigationController:
            let navigationController = presentedViewController as! UINavigationController
            return topViewController(for: navigationController.viewControllers.last)
        case is UITabBarController:
            let tabBarController = presentedViewController as! UITabBarController
            return topViewController(for: tabBarController.selectedViewController)
        default:
            return topViewController(for: presentedViewController)
        }
    }


}
