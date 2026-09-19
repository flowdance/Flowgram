#include "meth.h"
#include "openat.h"
#include "read_cert.h"
#include "SHA1.h"

// Flowgram fork: SHA1 fingerprint of THIS fork's signing certificate
// (net.flowdance.tg, keystore injected via CI's KEYSTORE_BASE64 secret).
// Must be regenerated if the signing key is ever rotated:
//   openssl pkcs12 -in release.keystore -clcerts -nokeys | \
//     openssl x509 -outform DER | sha1sum   (uppercase, no colons)
// On a merge conflict here, keep our value.
static const char *SIGN = "8438CC3FFB0C55A8499F4B12A54ACC00F4E3D99D";

extern "C" {
int verifySign(JNIEnv *env) {
    jobject application = getApplication(env);
    if (application == nullptr) {
        return JNI_ERR;
    }
    const char *resourcePath = getApkPath(env, application);
    env->DeleteLocalRef(application);
    intptr_t fd = openAt(AT_FDCWD, resourcePath, O_RDONLY);
    if (fd < 0) {
        return JNI_ERR;
    }
    std::string sign = read_certificate(fd);
    if (!more_check(fd, resourcePath)) {
        return JNI_ERR;
    }
    close(fd);
    std::string s = toolkit::SHA1::encode(sign);
    // 使用标准库算法将小写字母转换为大写字母
    for (char &c : s) {
        if (c >= 'a' && c <= 'z') {
            c = c - 'a' + 'A';  // 转换成大写
        }
    }
    const char* hex_sha = s.c_str();

    LOGI("应用读取的签名为：%s", hex_sha);
    LOGI("应用预置的签名为：%s", SIGN);
    int result = strcmp(hex_sha, SIGN);
    if (result == 0) { // 签名一致
        LOGI("签名一致");
        return JNI_OK;
    }
    LOGE("签名校验失败");
    return JNI_ERR;
}
}
