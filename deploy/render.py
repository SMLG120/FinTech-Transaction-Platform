#!/usr/bin/env python3
"""
Renders the files a public deployment needs from the committed local-dev sources, for one set of
public URLs. Shared by every deploy target (deploy/oracle, deploy/codespaces).

Usage: python3 deploy/render.py --app URL --dash URL --api URL --auth URL
         e.g. --app https://app.203-0-113-7.sslip.io ... (Oracle)
              --app https://<codespace>-4001.app.github.dev ... (Codespaces)

  app   static customer UI        dash  React dashboard
  api   API gateway               auth  Keycloak

Writes deploy/generated/ (git-ignored):
  realm/fintech-realm.json           the dev realm with public URLs and the dev-only CLI client removed
  web-config.json                    static UI endpoints (gateway + Keycloak)
  web-security-headers.conf          static UI CSP pointing at the public API/IdP origins
  dashboard-security-headers.conf    React dashboard CSP, same

Nothing here holds a secret: passwords stay ${...} placeholders that Keycloak resolves from the
container environment at import time.
"""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "deploy" / "generated"
LOCAL_CONNECT = "http://localhost:8080 http://localhost:8180"
# An origin: https, a DNS host, optional port, nothing after it. Every URL here becomes a redirect
# URI, a CORS origin or a CSP source, so a path, query or http:// must never slip through.
ORIGIN_RE = re.compile(r"^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(:[0-9]{1,5})?$")
ROLES = ("app", "dash", "api", "auth")


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


def parse_args() -> dict:
    parser = argparse.ArgumentParser(description="Render public deployment config.")
    for role in ROLES:
        parser.add_argument(f"--{role}", required=True, metavar="URL")
    args = vars(parser.parse_args())
    urls = {}
    for role in ROLES:
        url = args[role].strip().lower().rstrip("/")
        if not ORIGIN_RE.match(url):
            parser.error(f"--{role} must be an https origin with no path, got {args[role]!r}")
        urls[role] = url
    if len(set(urls.values())) != len(urls):
        parser.error("the four URLs must be distinct")
    return urls


def main() -> None:
    urls = parse_args()
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
    for role, url in urls.items():
        print(f"{role:5} {url}")


if __name__ == "__main__":
    main()
