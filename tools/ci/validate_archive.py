"""Check the structure of an unsigned Tsumugi .xcarchive (F-06). CI runs it after `xcodebuild archive`.

    python3 tools/ci/validate_archive.py build/Tsumugi.xcarchive

Catches what an unsigned CI compile never exercised and would otherwise surface at the owner's first
upload: usage strings, export-compliance and ATS keys, the compiled app icon, embedded extensions (widget, share,
action) with a privacy manifest each, the project's four shipping targets, native frameworks, privacy manifests, bundled licenses and content packs, document types, and the App Group in every
target's entitlements file. Standard library only; `xcrun assetutil` is used when available (macOS).
Exits 1 with a list of every problem found.
"""

from __future__ import annotations

import json
import plistlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP_NAME = "Tsumugi"
APP_GROUP = "group.app.tsumugi"
USAGE_STRINGS = [
    "NSCameraUsageDescription",
    "NSMicrophoneUsageDescription",
    "NSSpeechRecognitionUsageDescription",
    "NSLocalNetworkUsageDescription",
]
EXTENSIONS = {
    "TsumugiWidget.appex": "com.apple.widgetkit-extension",
    "TsumugiShare.appex": "com.apple.share-services",
    "TsumugiAction.appex": "com.apple.ui-services",
}
# The shipping targets in the Xcode project (the test bundle aside): the app and its three extensions.
SHIPPING_TARGETS = ["Tsumugi", "TsumugiWidget", "TsumugiShare", "TsumugiAction"]
NATIVE_FRAMEWORKS = ["llama.framework", "whisper.framework"]
DOCUMENT_TYPES = ["org.idpf.epub-container", "app.tsumugi.subrip", "app.tsumugi.webvtt", "app.tsumugi.apkg",
                  "app.tsumugi.item-bank", "public.json"]
ENTITLEMENTS = ["Tsumugi.entitlements", "TsumugiWidget.entitlements", "TsumugiShare.entitlements",
                "TsumugiAction.entitlements"]

problems: list[str] = []


def check(ok: bool, message: str) -> bool:
    if not ok:
        problems.append(message)
    return ok


def load_plist(path: Path) -> dict:
    if not check(path.is_file(), f"missing {path}"):
        return {}
    with open(path, "rb") as f:
        return plistlib.load(f)


def check_app_info(app: Path) -> dict:
    info = load_plist(app / "Info.plist")
    for key in USAGE_STRINGS:
        check(bool(str(info.get(key, "")).strip()), f"Info.plist: {key} missing or empty")
    check("ITSAppUsesNonExemptEncryption" in info, "Info.plist: ITSAppUsesNonExemptEncryption missing")
    check(info.get("ITSAppUsesNonExemptEncryption") is False,
          "Info.plist: ITSAppUsesNonExemptEncryption should be NO (RELEASE.md §5)")
    ats = info.get("NSAppTransportSecurity", {})
    check(ats.get("NSAllowsLocalNetworking") is True,
          "Info.plist: NSAppTransportSecurity.NSAllowsLocalNetworking not YES (TsumugiInfo.plist not merged?)")
    check(ats.get("NSAllowsArbitraryLoads") is not True, "Info.plist: NSAllowsArbitraryLoads must not be set")
    declared = {t for d in info.get("CFBundleDocumentTypes", []) for t in d.get("LSItemContentTypes", [])}
    for uti in DOCUMENT_TYPES:
        check(uti in declared, f"Info.plist: CFBundleDocumentTypes lacks {uti}")
    return info


def check_icon(app: Path, info: dict) -> None:
    check((app / "Assets.car").is_file(), "Assets.car missing: the asset catalog wasn't compiled")
    primary = info.get("CFBundleIcons", {}).get("CFBundlePrimaryIcon", {})
    check(primary.get("CFBundleIconName") == "AppIcon",
          "Info.plist: CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName is not AppIcon (icon not compiled)")
    check(any(app.glob("AppIcon*.png")), "no AppIcon*.png in the app bundle (actool emits them for iOS 17)")
    if shutil.which("xcrun") and (app / "Assets.car").is_file():
        out = subprocess.run(["xcrun", "--sdk", "iphoneos", "assetutil", "--info", str(app / "Assets.car")],
                             capture_output=True, text=True, check=False)
        try:
            entries = json.loads(out.stdout) if out.returncode == 0 else None
        except json.JSONDecodeError:
            entries = None
        if entries is None:
            print("  (assetutil output unreadable; relying on CFBundleIcons and the AppIcon PNGs)")
        else:
            icons = [e for e in entries if e.get("Name") == "AppIcon"]
            check(bool(icons), "Assets.car has no AppIcon renditions")
            print(f"  Assets.car: {len(icons)} AppIcon renditions")


def check_extensions(app: Path) -> None:
    for name, point in EXTENSIONS.items():
        appex = app / "PlugIns" / name
        if not check(appex.is_dir(), f"extension not embedded: PlugIns/{name}"):
            continue
        info = load_plist(appex / "Info.plist")
        got = info.get("NSExtension", {}).get("NSExtensionPointIdentifier")
        check(got == point, f"{name}: NSExtensionPointIdentifier is {got!r}, expected {point}")
        check(bool(info.get("CFBundleExecutable")) and (appex / info.get("CFBundleExecutable", "?")).is_file(),
              f"{name}: executable missing")
        check((appex / "PrivacyInfo.xcprivacy").is_file(), f"{name}: PrivacyInfo.xcprivacy missing")


def check_targets() -> None:
    """The project declares exactly the four shipping targets (plus the unit-test bundle), each embedded above."""
    pbx = REPO / "iosApp" / "Tsumugi.xcodeproj" / "project.pbxproj"
    if not check(pbx.is_file(), f"missing {pbx}"):
        return
    text = pbx.read_text(encoding="utf-8")
    declared = sorted(set(re.findall(r"isa = PBXNativeTarget;.*?\n\s*name = (\w+);", text, flags=re.S)))
    expected = sorted(SHIPPING_TARGETS + ["TsumugiTests"])
    check(declared == expected, f"project.pbxproj targets are {declared}, expected {expected}")
    shipping = [t for t in declared if t != "TsumugiTests"]
    check(len(shipping) == 4, f"expected 4 shipping targets, found {len(shipping)}: {shipping}")
    print(f"  targets: {declared}")


def check_frameworks(app: Path) -> None:
    for name in NATIVE_FRAMEWORKS:
        fw = app / "Frameworks" / name
        binary = fw / name.removesuffix(".framework")
        if check(fw.is_dir(), f"native framework not embedded: Frameworks/{name} "
                               "(did tools/models/fetch_ios_frameworks.sh run?)"):
            check(binary.is_file(), f"Frameworks/{name}: binary missing")
            check((fw / "Info.plist").is_file(), f"Frameworks/{name}: Info.plist missing")


def check_resources(app: Path) -> None:
    check((app / "PrivacyInfo.xcprivacy").is_file(), "app: PrivacyInfo.xcprivacy missing")
    licenses = app / "LICENSES.md"
    if check(licenses.is_file(), "app: LICENSES.md missing (Bundle Content Packs phase)"):
        check(licenses.read_text(encoding="utf-8").startswith("# Third-party licenses"),
              "app: LICENSES.md isn't docs/LICENSES.md")
    packs = app / "packs"
    if check(packs.is_dir(), "app: packs/ folder missing"):
        check((packs / "manifest.json").is_file(), "packs/manifest.json missing")
        check((packs / "dictionary.sqlite").is_file(), "packs/dictionary.sqlite missing (packs job artifact?)")
        print(f"  packs: {sorted(p.name for p in packs.iterdir())}")


def check_entitlements() -> None:
    """Unsigned archives carry no entitlements, so check the files each target signs with."""
    for name in ENTITLEMENTS:
        ent = load_plist(REPO / "iosApp" / name)
        groups = ent.get("com.apple.security.application-groups", [])
        check(APP_GROUP in groups, f"{name}: App Group {APP_GROUP} missing")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    archive = Path(sys.argv[1])
    app = archive / "Products" / "Applications" / f"{APP_NAME}.app"
    if not app.is_dir():
        sys.exit(f"FAIL: {app} not found")
    print(f"Validating {app}")
    info = check_app_info(app)
    check_icon(app, info)
    check_extensions(app)
    check_targets()
    check_frameworks(app)
    check_resources(app)
    check_entitlements()
    archive_info = load_plist(archive / "Info.plist")
    check(archive_info.get("ApplicationProperties", {}).get("CFBundleIdentifier") == "app.tsumugi.ios",
          "archive Info.plist: ApplicationProperties.CFBundleIdentifier is not app.tsumugi.ios")
    if problems:
        print(f"FAIL: {len(problems)} problem(s):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print("OK: archive structure valid")


if __name__ == "__main__":
    main()
