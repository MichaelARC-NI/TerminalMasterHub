# 🔧 Terminal Master Hub v1.5.5

**Terminal Master Hub** es una aplicación Android todo-en-uno que integra un emulador de terminal Linux completo (Ubuntu 24.04 ARM64) con entorno aislado, un **IDE de Python nativo** con renderizado gráfico, **herramientas profesionales de flasheo** USB OTG (ADB/Fastboot, Samsung Odin3, Xiaomi MiTool), y **editor de código embebido** con explorador de archivos.

> 🚨 **Probada en Android 16, Android 13, Android 14, 15 y 17.**  
> Funciona en dispositivos con y sin acceso Root.

---

## 📱 Características Principales

### 🖥️ Sesión 1 — Terminal & Python IDE
- **Emulador de terminal Linux** con `Typeface.MONOSPACE` y banner limpio adaptable
- **Python nativo ARM64** (CPython 3.14 compilado para bionic libc) sin necesidad de PRoot
- **Python vía Ubuntu/PRoot** con `apt`, `python3`, `pip`, `matplotlib`, `numpy`, etc.
- **Selector de salida gráfica**: Terminal o Ventana WebView para matplotlib/PIL
- **Editor de código embebido** para archivos `.py` y `.sh`
- **Historial de comandos** navegable con flechas ↑↓
- **Barra de herramientas táctil** con Tab, Esc, Ctrl, Alt, ↑↓, Home, End, Del, Files
- **Detección de root** multinivel (Magisk, KernelSU, APatch, SuperSU)

### 🐧 Entorno Ubuntu 24.04 ARM64 (PRoot)
- **PRoot embebido** en los assets del APK (~30MB Ubuntu rootfs comprimido)
- **Sin necesidad de root**: PRoot usa `ptrace` para ejecutar binarios glibc
- **Comandos**: `apt update`, `apt install python3 cmus git nano neovim zstd tar unzip`
- **Instalación automática**: `bootstrap proot install` extrae assets
- **Modo local vs Ubuntu**: `mode ubuntu` / `mode local`
- **`cmus`**: Reproductor de música en terminal integrado

### 🔌 Sesión 2 — USB Tools (ADB + Fastboot)
#### 📱 Sub-sesión ADB Shell
- **Consola ADB nativa** sobre USB OTG
- **Permisos USB automáticos** con `PendingIntent.FLAG_MUTABLE`
- **ADB Inalámbrico** por TCP/IP (pairing TLS + connect directo)
- **Cifrado RSA 2048 bits** para autenticación (generación automática de claves)
- **Historial de IPs** con autocompletado para reconexión rápida
- Comandos: `adb devices`, `adb shell`, `adb push`, `adb pull`

#### ⚡ Sub-sesión Fastboot
- **Cliente Fastboot completo**: `devices`, `flash`, `reboot-bootloader`, `oem`, `getvar`
- **Flasheo optimizado** con chunks de 1 MB
- **Desbloqueo de bootloader** (`flashing unlock`, `oem unlock`)
- Compatible con Xiaomi, POCO, Redmi, Samsung, Motorola, Google Pixel, OnePlus, Oppo, Huawei, LG, Sony, ASUS, HTC, Lenovo, ZTE y más

### 📦 Sesión 3 — Samsung Odin3
- **Protocolo Odin3 nativo** para Samsung en Download Mode
- Lectura de archivos `.tar` y `.tar.md5` vía OTG
- **Gestión de memoria** para archivos de sistema pesados
- **Selección de particiones**: BL, AP, CP, CSC, USERDATA

### 📁 Sesión 4 — Xiaomi Auto-Flasher
- **Descompresión inteligente** de ROMs Fastboot `.tgz` con Apache Commons Compress
- **Parseo de scripts**: detecta `flash_all.sh`, extrae comandos fastboot
- **Ejecución ordenada**: cola de comandos fastboot automática

### 📂 Explorador de Archivos
- **Navegación completa** del almacenamiento interno
- **Modo Root**: acceso a `/` (raíz del sistema)
- **Modo No-Root**: limitado a `/storage/emulated/0/`
- Integración con el entorno Linux: descompresión con `tar`, `unzip`, `zstd`

### 🛠️ Herramientas Adicionales
- **Paquetes Linux**: `python3`, `cmus`, `git`, `nano`, `neovim`, `zstd`, `p7zip`, `tar`, `unrar`, `unzip`
- **Paquetes Python**: matplotlib, numpy, pillow, requests, tqdm, beautifulsoup4, flask, scipy, pandas, pyyaml, rich, psutil
- **Binarios nativos**: ADB y Fastboot ARM64 estáticos (Android 11.0.0_r3) embebidos

---

## 📲 Información de Contacto

| Red | Enlace |
|-----|--------|
| **Desarrollador** | **Michael Antonio Rodriguez Condega** |
| **GitHub** | [MichaelARC-NI](https://github.com/MichaelARC-NI) |
| **Correo** | [androidmovil@proton.me](mailto:androidmovil@proton.me) |
| **Telegram** | [t.me/Michael_Antonio_Rodriguez](https://t.me/Michael_Antonio_Rodriguez) |
| **Facebook** | [facebook.com/share/1D1pfVdbXE](https://www.facebook.com/share/1D1pfVdbXE) |

---

## 📥 Instalación

1. Descarga el APK desde [GitHub Releases](https://github.com/MichaelARC-NI/TerminalMasterHub/releases)
2. Habilita "Instalar apps desconocidas" en Ajustes > Seguridad
3. Instala el APK manualmente
4. Abre la app y sigue las instrucciones en la terminal

> ⚠️ **Nota**: Los assets de Ubuntu y PRoot (~30MB) vienen EMBEBIDOS en el APK.  
> No es necesario descargar nada adicional después de instalar.

---

## 🏗️ Arquitectura del Proyecto

```
TerminalMasterHub/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── ubuntu/          # PRoot + Ubuntu rootfs (embebido)
│   │   │   ├── adb-native/      # ADB/Fastboot ARM64 estáticos
│   │   │   └── cpython/         # CPython 3.14 ARM64 nativo
│   │   ├── java/com/terminalmasterhub/
│   │   │   ├── core/
│   │   │   │   ├── adb/         # ADB/Fastboot nativo
│   │   │   │   ├── file/        # Gestor de archivos
│   │   │   │   ├── mitool/      # Xiaomi MiTool parser
│   │   │   │   ├── odin/        # Protocolo Samsung Odin3
│   │   │   │   ├── permissions/ # Gestor de permisos
│   │   │   │   ├── proot/       # PRoot + Ubuntu manager
│   │   │   │   ├── python/      # Py2Droid CPython manager
│   │   │   │   ├── root/        # Root checker + bootstrap
│   │   │   │   ├── usb/         # USB Host API core
│   │   │   │   └── wireless/    # ADB inalámbrico + RSA
│   │   │   ├── data/            # Terminal history
│   │   │   ├── ui/
│   │   │   │   ├── terminal/    # Terminal + Python IDE
│   │   │   │   ├── fastboot/    # ADB/Fastboot UI
│   │   │   │   ├── samsung/     # Odin3 UI
│   │   │   │   ├── xiaomi/      # MiTool UI
│   │   │   │   └── explorer/    # File explorer dialog
│   │   │   ├── MainActivity.kt
│   │   │   └── TerminalMasterHubApp.kt
│   │   └── res/                 # Layouts, drawables, values
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 📜 Licencia

Este proyecto está bajo la licencia MIT.  
**Desarrollado por Michael Antonio Rodriguez Condega**  
© 2026 Terminal Master Hub
