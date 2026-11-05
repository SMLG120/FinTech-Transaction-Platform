#!/usr/bin/env python3
"""
Drives the browser sign-in the UIs use — Authorization Code + PKCE (S256) on the public
`fintech-web` client — without a browser, and prints the access token on stdout.

Used by verify-frontend-lifecycle.sh to prove, against a live Keycloak, the exact flow the UIs
run: authorize with a challenge, submit Keycloak's own login form, receive ?code&state on the
registered redirect URI, and redeem code + verifier from the UI's Origin. Standard library only.

Usage: FINTECH_LOGIN_PASSWORD=... oidc-pkce-login.py KEYCLOAK_URL REALM REDIRECT_URI ORIGIN USERNAME
The password is read from the environment, never argv, so it does not appear in `ps`.
Exit codes: 0 token printed; 1 flow failed (reason on stderr).
"""
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def fail(reason: str) -> None:
    print(f"pkce login: {reason}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 6:
        fail("usage: oidc-pkce-login.py KEYCLOAK_URL REALM REDIRECT_URI ORIGIN USERNAME")
    keycloak, realm, redirect_uri, origin, username = sys.argv[1:]
    password = os.environ.get("FINTECH_LOGIN_PASSWORD", "")
    if not password:
        fail("FINTECH_LOGIN_PASSWORD is not set")

    base = f"{keycloak}/realms/{realm}/protocol/openid-connect"
    verifier = b64url(secrets.token_bytes(32))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    state = b64url(secrets.token_bytes(16))
    nonce = b64url(secrets.token_bytes(16))

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)

    authorize = f"{base}/auth?" + urllib.parse.urlencode({
        "response_type": "code",
        "client_id": "fintech-web",
        "redirect_uri": redirect_uri,
        "scope": "openid profile email",
        "state": state,
        "nonce": nonce,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })
    try:
        page = opener.open(authorize, timeout=15).read().decode()
    except urllib.error.HTTPError as error:
        fail(f"authorize answered HTTP {error.code}; is {redirect_uri} registered on fintech-web?")
    match = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', page) or re.search(
        r'<form[^>]*action="([^"]+)"[^>]*id="kc-form-login"', page)
    if not match:
        fail("Keycloak did not render its login form")
    action = html.unescape(match.group(1))

    # Keycloak marks its session cookies Secure. Browsers treat http://localhost as a secure
    # context and send them anyway; Python's cookie jar does not. Emulate the browser — for
    # loopback hosts only, so this never weakens a real TLS deployment.
    if urllib.parse.urlparse(keycloak).hostname in ("localhost", "127.0.0.1"):
        for cookie in jar:
            cookie.secure = False

    form = urllib.parse.urlencode({"username": username, "password": password, "credentialId": ""}).encode()
    try:
        response = opener.open(urllib.request.Request(action, data=form), timeout=15)
        fail(f"login form answered HTTP {response.status} instead of redirecting; wrong password or locked out?")
    except urllib.error.HTTPError as error:
        if error.code not in (302, 303):
            fail(f"login form answered HTTP {error.code}")
        location = error.headers.get("Location", "")

    if not location.startswith(redirect_uri):
        fail("Keycloak redirected somewhere other than the registered redirect URI")
    params = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    if params.get("state", [""])[0] != state:
        fail("state on the redirect does not match the one sent")
    if "code" not in params:
        fail(f"no code on the redirect: {params.get('error', ['?'])[0]}")

    token_request = urllib.request.Request(
        f"{base}/token",
        data=urllib.parse.urlencode({
            "grant_type": "authorization_code",
            "client_id": "fintech-web",
            "code": params["code"][0],
            "redirect_uri": redirect_uri,
            "code_verifier": verifier,
        }).encode(),
        headers={"Origin": origin, "Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        response = urllib.request.urlopen(token_request, timeout=15)
    except urllib.error.HTTPError as error:
        fail(f"code exchange answered HTTP {error.code}: {error.read()[:200]!r}")
    if response.headers.get("Access-Control-Allow-Origin") != origin:
        fail(f"token endpoint does not allow {origin}; a browser would block the response")
    body = json.load(response)

    claims_part = body.get("id_token", "").split(".")[1] if body.get("id_token") else ""
    claims = json.loads(base64.urlsafe_b64decode(claims_part + "=" * (-len(claims_part) % 4))) if claims_part else {}
    if claims.get("nonce") != nonce:
        fail("ID token nonce does not match")
    print(body["access_token"])


if __name__ == "__main__":
    main()
