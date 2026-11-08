#!/usr/bin/env python3
"""
Renders the per-domain files a public deployment needs from the committed local-dev sources.

Usage: python3 deploy/oracle/render.py PUBLIC_DOMAIN
       e.g. 203-0-113-7.sslip.io  or  myfintech.duckdns.org

Writes deploy/oracle/generated/ (git-ignored):
  realm/fintech-realm.json           the dev realm with public URLs and the dev-only CLI client removed
  web-config.json                    static UI endpoints (gateway + Keycloak)
  web-security-headers.conf          static UI CSP pointing at the public API/IdP origins
  dashboard-security-headers.conf    React dashboard CSP, same

Hostnames: app.<domain> (static UI), dash.<domain> (React dashboard), api.<domain> (gateway),
auth.<domain> (Keycloak). Nothing here holds a secret: passwords stay ${...} placeholders that
Keycloak resolves from the container environment at import time.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "deploy" / "oracle" / "generated"
LOCAL_CONNECT = "http://localhost:8080 http://localhost:8180"
DOMAIN_RE = re.compile(r"^(?=.{4,200}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")


def hosts(domain: str) -> dict:
    return {name: f"https://{name}.{domain}" for name in ("app", "dash", "api", "auth")}


def render_realm(urls: dict) -> dict:
    realm = json.loads((ROOT / "infrastructure/keycloak/realm/fintech-realm.dev.json").read_text())
    realm["displayName"] = "FinTech Platform (public demo)"
    # The password-grant client exists for local scripts only and must not exist in a deployed realm.
    realm["clients"] = [c for c in realm["clients"] if c["clientId"] != "fintech-cli"]
    web = next(c for c in realm["clients"] if c["clientId"] == "fintech-web")
    assert web["publicClient"] and not web["directAccessGrantsEnabled"], "fintech-web must be code+PKCE only"
    web["redirectUris"] = [f"{urls['app']}/", f"{urls['dash']}/auth/callback"]
    web["webOrigins"] = [urls["app"], urls["dash"]]
    web["attributes"]["post.logout.redirect.uris"] = f"{urls['app']}/##{urls['dash']}/login"
    # "external" (not "all"): auth-service reaches Keycloak over the private compose network.
    realm["sslRequired"] = "external"
    return realm


def render_csp(source: pathlib.Path, urls: dict) -> str:
    text = source.read_text()
    if LOCAL_CONNECT not in text:
        raise SystemExit(f"{source}: expected connect-src '{LOCAL_CONNECT}' to replace")
    return text.replace(LOCAL_CONNECT, f"{urls['api']} {urls['auth']}")


def main() -> None:
    if len(sys.argv) != 2 or not DOMAIN_RE.match(sys.argv[1].lower()):
        raise SystemExit("usage: render.py PUBLIC_DOMAIN   (e.g. 203-0-113-7.sslip.io)")
    domain = sys.argv[1].lower()
    urls = hosts(domain)

    (OUT / "realm").mkdir(parents=True, exist_ok=True)
    (OUT / "realm" / "fintech-realm.json").write_text(json.dumps(render_realm(urls), indent=2) + "\n")
    (OUT / "web-config.json").write_text(json.dumps({
        "gatewayBaseUrl": urls["api"],
        "keycloakBaseUrl": urls["auth"],
        "keycloakRealm": "fintech",
        "keycloakClientId": "fintech-web",
    }, indent=2) + "\n")
    (OUT / "web-security-headers.conf").write_text(render_csp(ROOT / "web/security-headers.conf", urls))
    (OUT / "dashboard-security-headers.conf").write_text(
        render_csp(ROOT / "frontend/fintech-dashboard/security-headers.conf", urls))
    for name, url in urls.items():
        print(f"{name:5} {url}")


if __name__ == "__main__":
    main()
