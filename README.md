# 🔧 Terminal Master Hub

**Terminal Master Hub** es una aplicación Android todo-en-uno que integra un emulador de terminal Linux con entorno aislado (PRoot + Ubuntu 24.04 ARM64), un IDE de Python con renderizado gráfico, y herramientas profesionales de flasheo para dispositivos Android vía USB OTG (ADB/Fastboot universal, Samsung Odin3 y Xiaomi MiTool).

Desarrollada por **Michael Antonio Rodriguez Condega**.

---

## 📱 Capturas

> *Próximamente: capturas de pantalla de cada sesión.*

---

## 🚀 Características Principales

### 🖥️ Sesión 1 — Terminal & Python IDE
- **Emulador de terminal Linux** con fuente monoespaciada (`Typeface.MONOSPACE`)
- **Banner de bienvenida limpio** sin bordes ASCII, se adapta al ancho de la pantalla
- **Entorno Ubuntu 24.04 ARM64 via PRoot**: instala paquetes con `apt`, ejecuta scripts `.sh` sin necesidad de **root**
- **Compatible con Android 14+**: usa `/system/bin/linker64` para evitar restricción `noexec` en `/data/data/`
- **Selector de salida Python**: al ejecutar un script `.py`, elige entre:
  - ✅ Terminal (salida de texto)
  - ✅ Ventana Gráfica (renderizado en WebView con matplotlib, PIL, seaborn, plotly, etc.)
- **Editor de código embebido** para crear y modificar archivos `.py` y `.sh`
- **Historial de comandos** navegable con flechas
- **Detección de root** multinivel compatible con Magisk, KernelSU, APatch y SuperSU

### 🔌 Sesión 2 — USB Tools (ADB + Fastboot)

#### 📱 Sub-sesión 2A — ADB Shell Interactivo
- **Consola ADB nativa** sobre USB OTG sin dependencias externas
- **Permisos USB automáticos**: solicita permiso al detectar dispositivo (FLAG_MUTABLE)
- **BroadcastReceiver completo**: escucha ACTION_USB_PERMISSION y conecta auto
- **ADB Inalámbrico**: conexión por TCP/IP (pairing + connect)
- Comandos: `adb devices`, `adb shell`, `adb push`, `adb pull`

#### ⚡ Sub-sesión 2B — Fastboot (Bootloader & Flasheo)
- **Cliente Fastboot completo**: `devices`, `flash`, `reboot`, `oem`, `getvar`
- **Permisos USB automáticos**: solicita permiso al detectar modo fastboot
- **Flasheo de particiones** con transferencia optimizada (chunks de 1 MB)
- **Desbloqueo de bootloader** (`flashing unlock`, `oem unlock`)
- Compatible con: **Xiaomi, POCO, Redmi, Samsung, Motorola, Google Pixel, OnePlus, Oppo, Huawei, LG, Sony, ASUS, HTC, Lenovo, ZTE** y más

### 🇸🇲 Sesión 3 — Samsung Odin3
- **Protocolo Odin3 nativo**: comunicación directa con Samsung en **Download Mode**
- **Flashing de firmware**: soporta archivos `.tar` y `.tar.md5`
- **Verificación MD5** integrada para validar integridad de firmware
- **Optimización de memoria RAM**: procesa archivos de varios GB en chunks de 512 KB para evitar OOM

### 🇨🇳 Sesión 4 — Xiaomi MiTool
- **Selector de ROM Fastboot** (formato `.tgz`) desde el almacenamiento interno
- **Extracción inteligente**: descomprime la ROM con Apache Commons Compress
- **Parser de `flash_all.sh`**: traduce scripts bash a comandos fastboot ejecutables
- **Flasheo automático**: ordena y ejecuta particiones secuencialmente

### 📂 Explorador de Archivos Visual
- **Navegación gráfica** del sistema de archivos con lista en `AlertDialog`
- **Modo Root**: navega desde la raíz `/` del sistema
- **Modo No-Root**: navega desde `/storage/emulated/0/`

---

## 🔧 Permisos Android

| Permiso | Propósito |
|---------|-----------|
| `USB_PERMISSION` + `android.hardware.usb.host` | Conexión OTG para ADB/Fastboot/Odin3 |
| `MANAGE_EXTERNAL_STORAGE` | Acceso completo al almacenamiento para ROMs y scripts |
| `INTERNET` + `ACCESS_NETWORK_STATE` | Descarga de rootfs Ubuntu y paquetes pip |
| `SYSTEM_ALERT_WINDOW` | Ventana flotante para salida gráfica de Python |
| `FOREGROUND_SERVICE` | Servicio en primer plano para flasheo en segundo plano |
| `POST_NOTIFICATIONS` | Notificaciones para servicio en primer plano |

---

## ⚙️ Cómo usar el entorno Linux

```bash
# 1. Instalar PRoot + Ubuntu (desde assets del APK o descarga)
bootstrap proot install

# 2. Activar modo Ubuntu
mode ubuntu

# 3. Usar comandos Linux normalmente
apt update
apt install python3 python3-pip cmus
python3 --version
cmus
```

### Sin necesidad de root
Gracias a PRoot + linker64, todo funciona **sin acceso root**. Los binarios se ejecutan via `/system/bin/linker64` para evitar la restricción `noexec` de Android 14+.

---

## 📲 Instalación

1. Descarga el APK desde la sección de **Releases** de GitHub
2. Instálalo manualmente en tu dispositivo Android (API 26+)
3. Concede los permisos solicitados al primer inicio

> **Nota**: En dispositivos con políticas restrictivas (HyperOS, MIUI, ColorOS), usa ADB:
> ```bash
> adb install TerminalMasterHub.apk
> ```

---

## 🛠 Compilar desde código

```bash
git clone https://github.com/MichaelARC-NI/TerminalMasterHub.git
cd TerminalMasterHub
./gradlew assembleDebug
```

### Requisitos
- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17
- Android SDK 35
- Kotlin 2.0+

---

## 📋 Notas técnicas

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15+)
- **Compile SDK**: 35
- **Lenguaje**: Kotlin
- **Arquitectura**: MVVM + Bottom Navigation + ViewPager2
- **Rootfs**: Ubuntu 24.04 ARM64 embebido en assets (29MB comprimido)
- **PRoot**: Binario arm64 embebido, ejecutado via linker64
- **Extracción TAR**: Apache Commons Compress (no necesita tar del sistema)
- **Package**: `com.terminalmasterhub`

---

## 🌐 Redes del Desarrollador

- **GitHub**: [MichaelARC-NI](https://github.com/MichaelARC-NI)
- **Email**: androidmovil@proton.me
- **Telegram**: [t.me/Michael_Antonio_Rodriguez](https://t.me/Michael_Antonio_Rodriguez)
- **Facebook**: [facebook.com/share/1D1pfVdbXE](https://www.facebook.com/share/1D1pfVdbXE)

---

## ⭐ Proyectos Relacionados

- [Michael Sombra](https://github.com/MichaelARC-NI/Sombra) — Sombra lateral personalizable para Android
- [ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool.git) — Herramienta de flasheo Samsung (referencia)
- [MiTool](https://github.com/offici5l/MiTool.git) — Herramienta de flasheo Xiaomi (referencia)

---

## 📄 Licencia

Este proyecto es de código abierto. Si te es útil, ¡considera dejar una estrella ⭐ en GitHub!
