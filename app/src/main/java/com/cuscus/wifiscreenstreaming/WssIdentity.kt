/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */
package com.cuscus.wifiscreenstreaming

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.SecureRandom

class WssIdentity private constructor(
    private val secret: Ed25519PrivateKeyParameters
) {

    val publicKey: ByteArray = secret.generatePublicKey().encoded

    val fingerprint: String get() = fingerprintOf(publicKey)

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, secret)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {

        const val KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64

        fun fingerprintOf(publicKey: ByteArray): String =
            WssCrypto.sha256(publicKey)
                .copyOf(8)
                .joinToString(":") { "%02X".format(it) }

        fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
            return runCatching {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
                verifier.update(data, 0, data.size)
                verifier.verifySignature(signature)
            }.getOrDefault(false)
        }

        fun loadOrCreate(file: File): WssIdentity {
            if (file.isFile) {
                val stored = runCatching { file.readBytes() }.getOrNull()
                if (stored != null && stored.size == KEY_SIZE) {
                    return WssIdentity(Ed25519PrivateKeyParameters(stored, 0))
                }
            }

            val material = ByteArray(KEY_SIZE)
            SecureRandom().nextBytes(material)
            val created = Ed25519PrivateKeyParameters(material, 0)

            file.parentFile?.mkdirs()
            file.writeBytes(material)
            restrictToOwner(file)
            return WssIdentity(created)
        }

        private fun restrictToOwner(file: File) {
            runCatching {
                val posix = java.nio.file.Files.getFileAttributeView(
                    file.toPath(),
                    java.nio.file.attribute.PosixFileAttributeView::class.java
                )
                if (posix != null) {
                    posix.setPermissions(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    )
                    return
                }
            }
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }
    }
}
