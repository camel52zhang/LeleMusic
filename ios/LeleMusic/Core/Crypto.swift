import Foundation
import CommonCrypto

// MARK: - eapi 加密所需的哈希/加密原语（对齐 Android NeteaseEapiResolver）

/// MD5 → 小写 hex
func md5Hex(_ text: String) -> String {
    let data = Data(text.utf8)
    var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
    data.withUnsafeBytes { buf in
        _ = CC_MD5(buf.baseAddress, CC_LONG(buf.count), &digest)
    }
    return digest.map { String(format: "%02x", $0) }.joined()
}

/// AES-128-ECB / PKCS7 → 小写 hex（eapi 公开密钥，非机密）
func aesEcbPkcs7Hex(_ text: String, key: String) -> String? {
    let data = Data(text.utf8)
    let keyData = Data(key.utf8)
    var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
    var outLen = 0
    let status = keyData.withUnsafeBytes { keyBuf in
        data.withUnsafeBytes { dataBuf in
            CCCrypt(
                CCOperation(kCCEncrypt),
                CCAlgorithm(kCCAlgorithmAES),
                CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                keyBuf.baseAddress, keyBuf.count,
                nil,
                dataBuf.baseAddress, dataBuf.count,
                &out, out.count, &outLen
            )
        }
    }
    guard status == kCCSuccess else { return nil }
    return out.prefix(outLen).map { String(format: "%02x", $0) }.joined()
}

/// base64 解码；失败原样返回（对齐 KugouLyricSource.decodeLrc）
func decodeBase64Lrc(_ content: String) -> String {
    if let data = Data(base64Encoded: content, options: [.ignoreUnknownCharacters]),
       let text = String(data: data, encoding: .utf8) {
        return text
    }
    return content
}
