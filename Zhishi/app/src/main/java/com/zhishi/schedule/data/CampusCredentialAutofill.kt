package com.zhishi.schedule.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Fills only the official ID credential form. Its own SM2 and fingerprint scripts perform login. */
object CampusCredentialAutofill {
    fun supports(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.host == "id.tsinghua.edu.cn" && u.port == 443 &&
            u.username.isEmpty() && u.password.isEmpty() &&
            Regex("^/do/off/ui/auth/login/form/[a-fA-F0-9]{32}(?:/[0-9]+)?$").matches(u.encodedPath)
    }

    fun script(url: String, credentials: CampusCredentials): String {
        require(supports(url))
        val path = url.toHttpUrlOrNull()!!.encodedPath
        return """
            (function() {
              if (location.origin !== 'https://id.tsinghua.edu.cn' || location.pathname !== ${JSONObject.quote(path)}) return 'manual';
              var f = document.getElementById('theform');
              if (!f || f.method.toLowerCase() !== 'post') return 'manual';
              var a = new URL(f.action, location.href);
              if (a.origin !== location.origin || a.pathname !== '/do/off/ui/auth/login/check') return 'manual';
              var u = f.querySelector('#i_user'), p = f.querySelector('#i_pass');
              if (!u || !p || p.type !== 'password' || typeof window.doLogin !== 'function' ||
                  typeof window.sm2Util === 'undefined' || !document.getElementById('sm2publicKey')) return 'manual';
              u.value = ${JSONObject.quote(credentials.studentId)};
              p.value = ${JSONObject.quote(credentials.password)};
              u.dispatchEvent(new Event('input', {bubbles:true}));
              p.dispatchEvent(new Event('input', {bubbles:true}));
              var trust = f.querySelector('input[name="singleLogin"]');
              if (trust) trust.checked = true;
              var name = f.querySelector('input[name="deviceName"]');
              if (name) name.value = '知时 · Android';
              var captcha = f.querySelector('#c_code');
              if (captcha && captcha.getClientRects().length > 0 && getComputedStyle(captcha).visibility !== 'hidden') return 'verification';
              window.doLogin();
              return 'submitted';
            })()
        """.trimIndent()
    }
}
