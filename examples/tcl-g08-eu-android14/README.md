# TCL Smart TV Pro (G08, Android TV 14), European firmware

TCL `G08` set, model "Smart TV Pro", product `G08_4K_GB`, fingerprint
`TCL/G08_4K_GB/G08:14/UTT2.250416.001/AU02:user/release-keys`, Android 14 — the OTA successor of
the Android 12 build documented in [tcl-g08-eu-android12](../tcl-g08-eu-android12/README.md).
Same board family (MediaTek MT9618) as the C755/QM6K/QM7 lists; the QM7 note in
[docs/devices/tcl-75qm751g-qm7.md](../../docs/devices/tcl-75qm751g-qm7.md) points at the same
2025-04 firmware line, this is the complete EU Android 14 list for it.

| File | Content |
|---|---|
| `debloat.txt` | The 56 packages disabled on this set, grouped and commented |

**How much testing is behind it:** one set, OTA'd from Android 12 to 14 and re-audited on 21 October
2026. All 56 packages are disabled on the live set (`pm list packages -d` verified), the OTA's newly
added bloat (channelplus, usercenter, waterfall.overseas, esticker) is included, the TV boots, plays
Live TV/HDMI, the remote's Source key opens the QuickPanel (log-injection verified), and the streaming
preloads plus the Google stack stay up. OTA-survival checked: no disabled package came back.

**What bit us (carried from the Android 12 pass, re-verified on 14):** `com.tcl.suspension`,
`com.tcl.interactive` and `com.tcl.ttvs` are load-bearing — disabling them kills the remote's Source
key (QuickPanel) and makes `vendor.tcl.tvinput.extend-service` spam null-binder NPEs at ~10 Hz. They
appear as commented DO-NOT-DISABLE lines.

**Remote app keys:** the RCU's YouTube/Netflix/Prime/Web keys are Google "featured keys" consumed by
whichever launcher is Home (the stock launcher reads the `global_key_app_map` global setting; with a
custom launcher as Home the same keys go to its app-key feature — on this project's launcher,
`app_keys` in `config.json`). `com.tcl.browser` is the Web-key target on this firmware — keep it.

Apply: `TV_HOST=<tv ip> tools/disable.sh examples/tcl-g08-eu-android14/debloat.txt`
Undo: `TV_HOST=<tv ip> tools/restore.sh examples/tcl-g08-eu-android14/debloat.txt`
