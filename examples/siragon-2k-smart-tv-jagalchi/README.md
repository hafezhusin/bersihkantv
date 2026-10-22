# SIRAGON 2K SMART TV (jagalchi / KTC MediaTek MT5867 / Google TV 14)

Configuración de debloat y reemplazo de pantalla de inicio (launcher estilo Apple TV) para televisores **SIRAGON 2K SMART TV**, ensamblados sobre placa ODM **KTC** (`jagalchi`, SoC MediaTek MT5867 / T56).

## Información Técnica del Dispositivo

| Parámetro | Valor detectado |
|---|---|
| **Marca Comercial** | SIRAGON |
| **Modelo Comercial** | 2K SMART TV (`KTC_SIRAGON_2K_25GV1`) |
| **Fabricante / ODM** | KTC (Shenzhen KTC Technology) |
| **Plataforma / Board** | `jagalchi` (SoC MediaTek `mt5867` / `t56`) |
| **Arquitectura CPU** | 32-bit ARM (`armeabi-v7a`) |
| **Versión de Android** | Android 14 (Google TV, API Level 34) |
| **Build ID** | `UKN6.260116.064` (release-keys) |
| **Lanzador de fábrica** | Google TV LauncherX (`com.google.android.apps.tv.launcherx`) |

---

## Archivos del Ejemplo

| Archivo | Función |
|---|---|
| `debloat.txt` | Lista de paquetes deshabilitados (telemetría, Anoki ACR, demos, bloatware y servicios prescindibles). |
| `restore.sh` | Script para revertir todos los cambios, re-habilitar los paquetes y regresar al lanzador original. |
| `backup/` | Respaldo completo de estado inicial de fábrica (`packages_all.txt`, `getprop.txt`, `settings_*.txt`). |
| `shots/` | Capturas de pantalla verificadas paso a paso durante el proceso. |

---

## Servicios Conservados
- **Chromecast built-in** (`com.google.android.apps.mediashell`): Conservado y plenamente funcional.
- **Teclado en pantalla (Gboard)** (`com.google.android.inputmethod.latin`): Intacto.
- **Entradas TV / HDMI / Sintonizador** (`com.mediatek.tvinput`, `com.mediatek.wwtv.tvcenter`): Intactos.
- **Google Play Store y Play Services**: Intactos para actualizaciones y descargas.
- **Controles Remotos y Bluetooth**: Totalmente operativos.

---

## Dock Personalizado en el Nuevo Launcher
Configurado para 4 accesos directos principales en la barra inferior:
1. **SmartTube / TizenTube** (`io.gh.reisxd.tizentube.cobalt`)
2. **Magis TV** (`com.android.mgstv`)
3. **Administrador de Archivos +** (`com.alphainventor.filemanager`)
4. **Google Play Store** (`com.android.vending`)

Los accesos a **Entradas HDMI / AV** y **Ajustes del Sistema** se encuentran en la barra superior como botones rápidos ("pills") estilo Apple TV.

---

## Cómo Restaurar el Televisor a su Estado Original

Si en algún momento deseas regresar a la pantalla de inicio original de Google TV o reactivar algún servicio:

```bash
# Para restaurar absolutamente todo de forma automática:
TV_HOST=192.168.31.169 bash examples/siragon-2k-smart-tv-jagalchi/restore_siragon.sh
```

O para reactivar una aplicación específica individualmente:

```bash
# Ejemplo: reactivar el Asistente de Google
adb -s 192.168.31.169:5555 shell pm enable --user 0 com.google.android.katniss
```

---

## Cómo Cambiar el Orden del Dock o el Fondo de Pantalla

1. Edita el archivo `launcher/app/src/main/res/values/config.xml` y modifica el bloque `<string-array name="dock_apps">`.
2. Vuelve a ejecutar:
   ```bash
   TV_HOST=192.168.31.169 ANDROID_HOME=/home/erp/Android/Sdk bash launcher/build-and-install.sh
   ```
