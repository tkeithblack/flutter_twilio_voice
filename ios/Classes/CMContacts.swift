//
//  CMContacts.swift
//
//  Created by Keith Black on 2/20/18.
//

import Foundation
import Contacts
//import CallLynkUtils

class ContactCacheElement {
    var name: String?
    var image: UIImage?
    
    required init(name: String?, image: UIImage? ) {
        self.name = name
        self.image = image
    }
}

// Threadsafe Contact Cache
class ContactCache {
    var cachedContacts = [String:ContactCacheElement]()
    let dictionaryAccessQueue = DispatchQueue(label: "DictWriteQueue")
    
    func addContact(key: String, name: String, image: UIImage?) {
        dictionaryAccessQueue.async(flags: .barrier) {
            self.cachedContacts[key] = ContactCacheElement(name: name, image: image)
        }
    }
    
    func removeContact(key: String) {
        dictionaryAccessQueue.async(flags: .barrier) {
            if self.cachedContacts[key] != nil {
                self.cachedContacts[key] = nil
            }
        }
    }
    
    func getContact(key: String, completionHandler:(ContactCacheElement?)->Void) {
        dictionaryAccessQueue.sync() {
            completionHandler(cachedContacts[key])
        }
    }
    
    public func flushCache() {
        dictionaryAccessQueue.async(flags: .barrier) {
            self.cachedContacts.removeAll()
        }
    }
}

public class CMContacts {
    static var cmContacts = CMContacts()
    
    let contactStore = CNContactStore()
    var contactCache = ContactCache()
    //    var contactCache = [String:ContactCacheElement]()
    let dictionaryAccessQueue = DispatchQueue(label: "DictWriteQueue")
    
    public init() {
        NotificationCenter.default.addObserver( self, selector: #selector(addressBookDidChange),
                                                name: NSNotification.Name.CNContactStoreDidChange, object: nil)
    }
    
    @objc func addressBookDidChange(notification: NSNotification){
        CMContacts.cmContacts.loadCache(){}
    }
    
    var haveAskedForContactsPermission = false
    
    public func loadCache(completionHandler: (() -> Void)?) {
        print("Contact Load cache called")
        contactCache.flushCache()
        
        DispatchQueue.global(qos: .background).async {
            if CNContactStore.authorizationStatus(for: CNEntityType.contacts) == .authorized {
                let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactPhoneNumbersKey, CNContactOrganizationNameKey, CNContactImageDataKey] as [CNKeyDescriptor]
                
                let contactsStore = CNContactStore()
                do {
                    try contactsStore.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) {
                        (contact, error) in
                        if (!contact.phoneNumbers.isEmpty) {
                            for phoneNumber in contact.phoneNumbers {
                                if let contactPhoneNumber = phoneNumber.value.stringValue.phoneNumberStorageFormat() {
                                    var fullName = contact.givenName
                                    if !contact.familyName.isEmpty && !fullName.isEmpty {
                                        fullName += " "
                                    }
                                    fullName += contact.familyName
                                    if fullName.isEmpty {
                                        fullName = contact.organizationName
                                    }
                                    
                                    // check for image
                                    var image:UIImage?
                                    
                                    if contact.isKeyAvailable(CNContactImageDataKey) {
                                        if let imageData = contact.imageData {
                                            image = UIImage(data: imageData)
                                        }
                                    }
                                    let name = fullName.isEmpty ? nil : fullName
                                    if name != nil {
                                        self.contactCache.addContact(key: contactPhoneNumber, name: name!, image: image)
                                    }
                                }
                            }
                        }
                    }
                }
                catch {
                    print("Unable to fetch contacts")
                    completionHandler?()
                    return
                }
            }
            completionHandler?()
            return
        }
    }
    
    
    public func getContactName(phoneNumber: String, returnImage:Bool=true, completionHandler: @escaping (_ contactName: String?, _ contactImage: UIImage?) -> Void) {
        
        guard let inputPhoneNumber = phoneNumber.phoneNumberStorageFormat(), inputPhoneNumber.isEmpty == false else {
            DispatchQueue.main.async {
                completionHandler(nil, nil)
            }
            return
        }
        
        contactCache.getContact(key: inputPhoneNumber, completionHandler: {
            cachedContact in
            if let contact = cachedContact {
                DispatchQueue.main.async {
                    completionHandler(contact.name,  contact.image)
                }
                return
            }
            DispatchQueue.global(qos: .background).async {
                let defaultKeys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactPhoneNumbersKey, CNContactOrganizationNameKey]
                let imageKey:[String]? = returnImage ? [CNContactImageDataKey] : nil
                let keys = self.combineKeys(keyList1: defaultKeys, keylist2: imageKey) as [CNKeyDescriptor]
                var contacts = [CNContact]()
                
                let contactsStore = CNContactStore()
                do {
                    try contactsStore.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) {
                        (contact, error) in
                        if (!contact.phoneNumbers.isEmpty) {
                            for phoneNumber in contact.phoneNumbers {
                                let phoneNumberToCompare = phoneNumber.value.stringValue.phoneNumberStorageFormat()
                                
                                if phoneNumberToCompare == inputPhoneNumber {
                                    contacts.append(contact)
                                    print("Number found: \(String(describing: inputPhoneNumber))")
                                }
                            }
                        }
                    }
                }
                catch {
                    print("Unable to fetch contacts")
                    DispatchQueue.main.async {
                        completionHandler(nil, nil)
                    }
                    return
                }
                
                if contacts.count == 0 {
                    //                        print("No contacts were found matching the given name.")
                    DispatchQueue.main.async {
                        completionHandler(nil, nil)
                    }
                } else {
                    let contact = contacts[0]
                    var fullName = contact.givenName
                    if !contact.familyName.isEmpty && !fullName.isEmpty {
                        fullName += " "
                    }
                    fullName += contact.familyName
                    if fullName.isEmpty {
                        fullName = contact.organizationName
                    }
                    
                    // check for image
                    var image:UIImage?
                    
                    if contact.isKeyAvailable(CNContactImageDataKey) {
                        if let imageData = contact.imageData {
                            image = UIImage(data: imageData)
                        }
                    }
                    
                    let name = fullName.isEmpty ? nil : fullName
                    if name != nil {
                        self.contactCache.addContact(key: inputPhoneNumber, name: name!, image: image)
                    }
                    DispatchQueue.main.async {
                        completionHandler(name, image)
                    }
                    return
                }
            }
            
        })
    }
    
    func combineKeys(keyList1:[String], keylist2:[String]?) -> [String] {
        var combinedList = keyList1
        if keylist2 != nil {
            for key in keylist2! {
                if combinedList.contains(where: { $0 == key }) == false {
                    combinedList.append(key)
                }
            }
        }
        return combinedList
    }
    
}

extension String {
    public func phoneNumberStorageFormat() -> String? {
        return formatPhoneNumberForStorage(phoneNumber: self)
    }
}

private func formatPhoneNumberForStorage(phoneNumber sourcePhoneNumber: String) -> String? {
    // Remove any character that is not a number
    let numbersOnly = sourcePhoneNumber.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
    let length = numbersOnly.count
    let hasLeadingOne = numbersOnly.hasPrefix("1")
    
    if length == 10 {
        return "+1\(numbersOnly)"
    } else if length == 11 && hasLeadingOne {
        return "+\(numbersOnly)"
    } else {
        return nil
    }
}

