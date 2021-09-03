//
//  Macros.swift
//  PhoneLynk
//
//  Created by Keith Black on 4/6/16.
//  Copyright © 2016 Tutorial. All rights reserved.
//

import Foundation
//import Firebase

// dLog and aLog macros to abbreviate NSLog.
// Use like this:
//
//   dLog("Log this!")
//
#if DEBUG
    func dLog(_ message:  @autoclosure () -> String, filename: NSString = #file, function: String = #function, line: Int = #line) {
        NSLog("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - %@", message())
//        print("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - \(message())")
    }
    func dLogFunc(_ message:  @autoclosure () -> String?=nil, filename: NSString = #file, function: String = #function, line: Int = #line) {
        NSLog("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - %@", message() ?? "")
        print("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - \(message() ?? "")")
    }
#else
    func dLog(_ message:  @autoclosure () -> String, filename: String = #file, function: String = #function, line: Int = #line) {
    }
    func dLogFunc(_ message:  @autoclosure () -> String?=nil) {
    }
#endif
func aLog(_ message: String, filename: NSString = #file, function: String = #function, line: Int = #line) {
    NSLog("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - %@", message)
    print("[\(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: "")):\(line)] \(function) - \(message)")
}

//func analyticsLogFunc(_ message:  @autoclosure () -> String?=nil, filename: NSString = #file, function: String = #function, line: Int = #line) {
//    Analytics.logEvent(filename.lastPathComponent.replacingOccurrences(of: ".swift", with: ""), parameters: ["function" : function])
//}


func notYetImplemented(vc: UIViewController) {
    let alertController = UIAlertController(title: "Oops!",
                                            message: "Sorry, this feature has not been implemented yet.", preferredStyle: .alert)
    let okAction = UIAlertAction(title: "OK", style: .default, handler: nil )
    alertController.addAction(okAction)
    vc.present(alertController, animated: true, completion: nil )
}
