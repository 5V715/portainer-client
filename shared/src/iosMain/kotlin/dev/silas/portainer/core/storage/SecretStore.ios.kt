@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.silas.portainer.core.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

private const val SERVICE = "dev.silas.portainer"

/** Keychain generic-password items: service `dev.silas.portainer`, account = key. */
private class KeychainSecretStore : SecretStore {
    /** Builds an identifying query for [key]; [extra] adds more entries. Caller must CFRelease the result. */
    private fun query(key: String, extra: (CFMutableDictionaryRef) -> Unit = {}): CFMutableDictionaryRef {
        val dict = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        addString(dict, kSecAttrService, SERVICE)
        addString(dict, kSecAttrAccount, key)
        extra(dict)
        return dict
    }

    // The dictionary retains its values, so drop the +1 from CFBridgingRetain after adding.
    private fun addString(dict: CFMutableDictionaryRef, attr: kotlinx.cinterop.COpaquePointer?, value: String) {
        val ref = CFBridgingRetain(value)
        CFDictionaryAddValue(dict, attr, ref)
        CFRelease(ref)
    }

    override suspend fun get(key: String): String? = memScoped {
        val q = query(key) {
            CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(it, kSecMatchLimit, kSecMatchLimitOne)
        }
        try {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(q, result.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            val bytes = ByteArray(data.length.toInt())
            if (bytes.isNotEmpty()) {
                bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            }
            bytes.decodeToString()
        } finally {
            CFRelease(q)
        }
    }

    override suspend fun put(key: String, value: String) {
        val bytes = value.encodeToByteArray()
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        }
        var dataRef: CFTypeRef? = null
        var q: CFMutableDictionaryRef? = null
        var attrs: CFMutableDictionaryRef? = null
        try {
            dataRef = CFBridgingRetain(data)
            q = query(key)
            attrs = CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            CFDictionaryAddValue(attrs, kSecValueData, dataRef)
            var status = SecItemUpdate(q, attrs)
            if (status == errSecItemNotFound) {
                CFDictionaryAddValue(q, kSecValueData, dataRef)
                CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                status = SecItemAdd(q, null)
            }
            check(status == errSecSuccess) { "Keychain write failed ($status)" }
        } finally {
            attrs?.let { CFRelease(it) }
            q?.let { CFRelease(it) }
            dataRef?.let { CFRelease(it) }
        }
    }

    override suspend fun remove(key: String) {
        val q = query(key)
        try {
            SecItemDelete(q)
        } finally {
            CFRelease(q)
        }
    }
}

actual fun createSecretStore(): SecretStore = KeychainSecretStore()
