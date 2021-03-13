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
    case connectFailed          = "connect_failed"
    case call_ended             = "call_ended"
    case unhold                 = "unhold"
    case hold                   = "hold"
    case unmute                 = "unmute"
    case mute                   = "mute"
    case speaker_on             = "speaker_on"
    case speaker_off            = "speaker_off"
    case audio_route_change     = "audio_route_change"
}
enum CallDirection: String {
    case incoming = "incoming"
    case outgoing = "outgoing"
}

public class SwiftFlutterTwilioVoicePlugin: NSObject, FlutterPlugin,  FlutterStreamHandler, PKPushRegistryDelegate, TVONotificationDelegate, TVOCallDelegate, AVAudioPlayerDelegate, CXProviderDelegate {

    var _result: FlutterResult?
    private var eventSink: FlutterEventSink?

    //var baseURLString = ˝""
    // If your token server is written in PHP, accessTokenEndpoint needs .php extension at the end. For example : /accessToken.php
    //var accessTokenEndpoint = "/accessToken"
    var accessToken:String?
    var identity = "alice"
    var callTo: String = "error"
    var deviceTokenString: String?
    var callArgs: Dictionary<String, AnyObject> = [String: AnyObject]()

    var voipRegistry: PKPushRegistry
    var incomingPushCompletionCallback: (()->Swift.Void?)? = nil

   var callInvite:TVOCallInvite?
   var call:TVOCall?
   var callKitCompletionCallback: ((Bool)->Swift.Void?)? = nil
   var audioDevice: TVODefaultAudioDevice = TVODefaultAudioDevice()

   var callKitProvider: CXProvider
   var callKitCallController: CXCallController
   var userInitiatedDisconnect: Bool = false
   var callOutgoing: Bool = false
    
    static var appName: String {
        get {
            return (Bundle.main.infoDictionary!["CFBundleDisplayName"] as? String) ?? "Define CFBundleDisplayName"
        }
    }

    public override init() {

        //isSpinning = false
        voipRegistry = PKPushRegistry.init(queue: DispatchQueue.main)
        let configuration = CXProviderConfiguration(localizedName: SwiftFlutterTwilioVoicePlugin.appName)
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        if let callKitIcon = UIImage(named: "AppIcon") {
            configuration.iconTemplateImageData = callKitIcon.pngData()
        }

        callKitProvider = CXProvider(configuration: configuration)
        callKitCallController = CXCallController()

        //super.init(coder: aDecoder)
        super.init()

        callKitProvider.setDelegate(self, queue: nil)

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
    }


      deinit {
          // CallKit has an odd API contract where the developer must call invalidate or the CXProvider is leaked.
          callKitProvider.invalidate()
      }


    var audioDeviceInitialized = false
    func initAudioDevice() {
        /*
         * The important thing to remember when providing a TVOAudioDevice is that the device must be set
         * before performing any other actions with the SDK (such as connecting a Call, or accepting an incoming Call).
         * This function will make sure we do not initialize the audioDevice more than once.
         */
        if !audioDeviceInitialized {
            audioDeviceInitialized = true
            TwilioVoice.audioDevice = audioDevice
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
            initAudioDevice();
            NSLog("pushRegistry:attempting to register with twilio")
            TwilioVoice.register(withAccessToken: token, deviceToken: deviceToken) { (error) in
                if let error = error {
                    NSLog("An error occurred while registering: \(error.localizedDescription)")
                }
                else {
                    NSLog("Successfully registered for VoIP push notifications.")
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
        if (self.call != nil) {
           let muted = self.call!.isMuted
           self.call!.isMuted = !muted
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
            result(self.call != nil);
            return;
        }
    else if flutterCall.method == "sendDigits"
    {
        guard let digits = arguments["digits"] as? String else {return}
        if (self.call != nil) {
            self.call!.sendDigits(digits);
        }
    }
    /* else if flutterCall.method == "receiveCalls"
    {
        guard let clientIdentity = arguments["clientIdentifier"] as? String else {return}
        self.identity = clientIdentity;
    } */
    else if flutterCall.method == "holdCall" {
        if (self.call != nil) {

            let hold = self.call!.isOnHold
            self.call!.isOnHold = !hold
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
        if (self.call != nil && self.call?.state == .connected) {
            NSLog("hangUp method invoked")
            self.userInitiatedDisconnect = true
            performEndCallAction(uuid: self.call!.uuid)
            //self.toggleUIState(isEnabled: false, showCallControl: false)
        }
    }
    result(true)
  }

  func makeCall(to: String, displayName: String)
  {
        if (self.call != nil && self.call?.state == .connected) {
            self.userInitiatedDisconnect = true
            performEndCallAction(uuid: self.call!.uuid)
            //self.toggleUIState(isEnabled: false, showCallControl: false)
        } else {
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

    /* func fetchAccessToken() -> String? {
        let endpointWithIdentity = String(format: "%@?identity=%@", accessTokenEndpoint, identity)
        guard let accessTokenURL = URL(string: baseURLString + endpointWithIdentity) else {
            return nil
        }

        return try? String.init(contentsOf: accessTokenURL, encoding: .utf8)
    } */

    func checkRecordPermission(completion: @escaping (_ permissionGranted: Bool) -> Void) {
        switch AVAudioSession.sharedInstance().recordPermission {
        case AVAudioSessionRecordPermission.granted:
            // Record permission already granted.
            completion(true)
            break
        case AVAudioSessionRecordPermission.denied:
            // Record permission denied.
            completion(false)
            break
        case AVAudioSessionRecordPermission.undetermined:
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
          NSLog("pushRegistry:didUpdatePushCredentials:forType:")

          if (type != .voIP) {
              return
          }

          //guard let accessToken = fetchAccessToken() else {
          //    return
          //}

          let deviceToken = credentials.token.map { String(format: "%02x", $0) }.joined()

          NSLog("pushRegistry:attempting to register with twilio")
        if let token = accessToken {
            TwilioVoice.register(withAccessToken: token, deviceToken: deviceToken) { (error) in
                if let error = error {
                    NSLog("An error occurred while registering: \(error.localizedDescription)")
                }
                else {
                    NSLog("Successfully registered for VoIP push notifications.")
                }
            }
        }
          self.deviceTokenString = deviceToken
      }

      public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
          NSLog("pushRegistry:didInvalidatePushTokenForType:")

          if (type != .voIP) {
              return
          }

          self.unregister()
      }

      func unregister() {

          guard let deviceToken = deviceTokenString, let token = accessToken else {
              return
          }

          TwilioVoice.unregister(withAccessToken: token, deviceToken: deviceToken) { (error) in
              if let error = error {
                  NSLog("An error occurred while unregistering: \(error.localizedDescription)")
              } else {
                  NSLog("Successfully unregistered from VoIP push notifications.")
              }
          }

          self.deviceTokenString = nil
      }

    /**
         * Try using the `pushRegistry:didReceiveIncomingPushWithPayload:forType:withCompletionHandler:` method if
         * your application is targeting iOS 11. According to the docs, this delegate method is deprecated by Apple.
         */
        public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType) {
            NSLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:")

            if (type == PKPushType.voIP) {
                TwilioVoice.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: nil)
            }
        }

        /**
         * This delegate method is available on iOS 11 and above. Call the completion handler once the
         * notification payload is passed to the `TwilioVoice.handleNotification()` method.
         */
        public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
            NSLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:completion:")
            // Save for later when the notification is properly handled.
            self.incomingPushCompletionCallback = completion

            if (type == PKPushType.voIP) {
                TwilioVoice.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: nil)
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

        // MARK: TVONotificaitonDelegate
    public func callInviteReceived(_ ci: TVOCallInvite) {
            NSLog("callInviteReceived:")

        // Twilio allows sending custom parameters. The code below checks to see if there is a
        // parameters called callerId, if so we'll use that number. This allows us to show the
        // actual from callerId rather than the 'client:ID' notation that may be present when
        // calling from a server/mobile device.
        // If this is not provided we'll use the standard callInvite.from.
        var callerId:String?
            if let parameters = ci.customParameters {
                callerId = parameters["callerId"] ?? ci.from
            }

            initAudioDevice();
            let from:String = callerId ?? "Voice Bot"

            reportIncomingCall(from: from, uuid: ci.uuid)
            self.callInvite = ci
                
        var inviteInfo:[String : Any] = ["event": CallState.call_invite.rawValue, "from": from, "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid];
            if let parameters = ci.customParameters {
                inviteInfo.updateValue(parameters, forKey: "customParameters")
            }
            sendPhoneCallEvents(json: inviteInfo)
        }

    public func cancelledCallInviteReceived(_ cancelledCallInvite: TVOCancelledCallInvite, error: Error) {
            NSLog("cancelledCallInviteCanceled:")

            if (self.callInvite == nil) {
                NSLog("No pending call invite")
                return
            }

            if let ci = self.callInvite {
                performEndCallAction(uuid: ci.uuid)
            }
        
            let ci = cancelledCallInvite;
        var inviteInfo:[String : Any] = ["event": CallState.call_invite_canceled.rawValue, "from": ci.from ?? "", "to": ci.to, "direction": CallDirection.incoming.rawValue, "sid": ci.callSid];
            if let parameters = ci.customParameters {
                inviteInfo.updateValue(parameters, forKey: "customParameters")
            }
            sendPhoneCallEvents(json: inviteInfo)
        }

        // MARK: TVOCallDelegate
    public func callDidStartRinging(_ call: TVOCall) {
        var callInfo = callToJson(call: call);
        callInfo.updateValue(CallState.ringing.rawValue, forKey: "event")
        sendPhoneCallEvents(json: callInfo)
        }

    public func callDidConnect(_ call: TVOCall) {
        var callInfo = callToJson(call: call);
        callInfo.updateValue(CallState.connected.rawValue, forKey: "event")
        sendPhoneCallEvents(json: callInfo)
        
        self.callKitCompletionCallback!(true)
        toggleAudioRoute(toSpeaker: false)
    }
    
        public func call(_ call: TVOCall, isReconnectingWithError error: Error) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.reconnecting.rawValue, forKey: "event")
            callInfo.updateValue(error.localizedDescription, forKey: "error")
            sendPhoneCallEvents(json: callInfo)
            NSLog("call:isReconnectingWithError:")
        }

        public func callDidReconnect(_ call: TVOCall) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.reconnected.rawValue, forKey: "event")
            sendPhoneCallEvents(json: callInfo)
            NSLog("callDidReconnect:")
        }

        public func call(_ call: TVOCall, didFailToConnectWithError error: Error) {
            var callInfo = callToJson(call: call);
            callInfo.updateValue(CallState.connectFailed.rawValue, forKey: "event")
            callInfo.updateValue(error.localizedDescription, forKey: "error")
            sendPhoneCallEvents(json: callInfo)
            
            NSLog("Call failed to connect: \(error.localizedDescription)")

            if let completion = self.callKitCompletionCallback {
                completion(false)
            }

            performEndCallAction(uuid: call.uuid)
            callDisconnected()
        }

    public func call(_ call: TVOCall, didDisconnectWithError error: Error?) {
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

                self.callKitProvider.reportCall(with: call.uuid, endedAt: Date(), reason: reason)
            }

            callDisconnected()
        }

        func callDisconnected() {
            if (self.call != nil) {
                self.call = nil
            }
            if (self.callInvite != nil) {
                self.callInvite = nil
            }

            self.callOutgoing = false
            self.userInitiatedDisconnect = false

            //stopSpin()
            //toggleUIState(isEnabled: true, showCallControl: false)
            //self.placeCallButton.setTitle("Call", for: .normal)
        }


        // MARK: AVAudioSession
        func toggleAudioRoute(toSpeaker: Bool) {
            // The mode set by the Voice SDK is "VoiceChat" so the default audio route is the built-in receiver. Use port override to switch the route.
            audioDevice.block = {
                kTVODefaultAVAudioSessionConfigurationBlock()
                do {
                    if (toSpeaker) {
                        try AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
                    } else {
                        try AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
                    }
                } catch {
                    NSLog(error.localizedDescription)
                }
            }
            audioDevice.block()
        }

    // MARK: CXProviderDelegate
        public func providerDidReset(_ provider: CXProvider) {
            NSLog("providerDidReset:")
            audioDevice.isEnabled = true
        }

        public func providerDidBegin(_ provider: CXProvider) {
            NSLog("providerDidBegin")
        }

        public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
            NSLog("provider:didActivateAudioSession:")
            audioDevice.isEnabled = true
        }

        public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
            NSLog("provider:didDeactivateAudioSession:")
        }

        public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
            NSLog("provider:timedOutPerformingAction:")
        }

        public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
            NSLog("provider:performCXStartCallAction:")

            //toggleUIState(isEnabled: false, showCallControl: false)
            //startSpin()

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
            NSLog("provider:performAnswerCallAction:")

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
            NSLog("provider:performEndCallAction:")

            audioDevice.isEnabled = true

            if (self.call != nil) {
                NSLog("provider:performEndCallAction: disconnecting call")
                self.call?.disconnect()
                //self.callInvite = nil
                //self.call = nil
                action.fulfill()
                return
            }

            if (self.callInvite != nil) {
                NSLog("provider:performEndCallAction: rejecting call")
                self.callInvite?.reject()
                //self.callInvite = nil
                //self.call = nil
                action.fulfill()
                return
            }
        }

        public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
            NSLog("provider:performSetHeldAction:")
            if let call = self.call {
                call.isOnHold = action.isOnHold
                action.fulfill()
            } else {
                action.fail()
            }
        }

        public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
            NSLog("provider:performSetMutedAction:")

            if let call = self.call {
                call.isMuted = action.isMuted
                action.fulfill()
            } else {
                action.fail()
            }
        }

        // MARK: Call Kit Actions
        func performStartCallAction(uuid: UUID, handle: String) {
            let callHandle = CXHandle(type: .generic, value: handle)
            let startCallAction = CXStartCallAction(call: uuid, handle: callHandle)
            let transaction = CXTransaction(action: startCallAction)

            callKitCallController.request(transaction)  { error in
                if let error = error {
                    NSLog("StartCallAction transaction request failed: \(error.localizedDescription)")
                    return
                }

                NSLog("StartCallAction transaction request successful")

                let callUpdate = CXCallUpdate()
                callUpdate.remoteHandle = callHandle
                callUpdate.supportsDTMF = true
                callUpdate.supportsHolding = true
                callUpdate.supportsGrouping = false
                callUpdate.supportsUngrouping = false
                callUpdate.hasVideo = false

                self.callKitProvider.reportCall(with: uuid, updated: callUpdate)
            }
        }

        func reportIncomingCall(from: String, uuid: UUID) {
            let callHandle = CXHandle(type: .generic, value: from)

            let callUpdate = CXCallUpdate()
            callUpdate.remoteHandle = callHandle
            callUpdate.supportsDTMF = true
            callUpdate.supportsHolding = true
            callUpdate.supportsGrouping = false
            callUpdate.supportsUngrouping = false
            callUpdate.hasVideo = false

            callKitProvider.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
                if let error = error {
                    NSLog("Failed to report incoming call successfully: \(error.localizedDescription).")
                } else {
                    NSLog("Incoming call successfully reported.")
                }
            }
        }

        func performEndCallAction(uuid: UUID) {

            NSLog("performEndCallAction method invoked")

            let endCallAction = CXEndCallAction(call: uuid)
            let transaction = CXTransaction(action: endCallAction)

            callKitCallController.request(transaction) { error in
                if let error = error {
                    self.sendErrorEvent(message: "End Call Failed: \(error.localizedDescription).")
                } else {
                    self.sendPhoneCallEvents(json: ["event": CallState.call_ended.rawValue])
                }
            }
        }

        func performVoiceCall(uuid: UUID, client: String?, completionHandler: @escaping (Bool) -> Swift.Void) {
            guard let token = accessToken else {
                completionHandler(false)
                return
            }

            let connectOptions: TVOConnectOptions = TVOConnectOptions(accessToken: token) { (builder) in
                builder.params = ["PhoneNumber": self.callTo, "From": self.identity]
                for (key, value) in self.callArgs {
                    if (key != "to" && key != "toDisplayName" && key != "from") {
                        builder.params[key] = "\(value)"
                    }
                }
                builder.uuid = uuid
            }
            let theCall = TwilioVoice.connect(with: connectOptions, delegate: self)
            self.call = theCall
            self.callKitCompletionCallback = completionHandler
        }

        func performAnswerVoiceCall(uuid: UUID, completionHandler: @escaping (Bool) -> Swift.Void) {
            if let ci = self.callInvite {
                let acceptOptions: TVOAcceptOptions = TVOAcceptOptions(callInvite: ci) { (builder) in
                    builder.uuid = ci.uuid
                }
                NSLog("performAnswerVoiceCall: answering call")
                let theCall = ci.accept(with: acceptOptions, delegate: self)
                self.call = theCall
                self.callKitCompletionCallback = completionHandler

                guard #available(iOS 13, *) else {
                    self.incomingPushHandled()
                    return
                }
            } else {
                NSLog("No CallInvite matches the UUID")
            }
        }

    public func onListen(withArguments arguments: Any?,
                         eventSink: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = eventSink

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(TVOCallDelegate.call(_:didDisconnectWithError:)),
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
        NSLog("Call Event: \(json)")
        guard let eventSink = eventSink else {
            return
        }
        eventSink(json)
    }

        // MARK: AV Functions (microphone handling)

    @objc func audioRouteChanged(_ notification:Notification) {

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
                    
                    let audioDevices = CMAudioUtils.audioDevices()
                    
                    self.sendPhoneCallEvents(json: ["event": CallState.audio_route_change.rawValue, 
                        "bluetooth_available": CMAudioUtils.isBluetoothsAudioInputAvailable(), 
                        "speaker_on": CMAudioUtils.isSpeakerOn(),
                        "devices" : CMAudioUtils.audioDevicesToJSON(audioDevices:audioDevices)
                    ])
                })
                break
            default:
                break
            }
        }
    }
    
    private func sendErrorEvent(message: String, details: String?=nil) {
        NSLog(message)
        guard let eventSink = eventSink else {
            return
        }
        eventSink(FlutterError(code: "unavailable",
                                message: message,
                                details: details))
    }
    
    internal func callToJson(call: TVOCall) -> [String : Any] {
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
