# 🔧 Terminal Master Hub

**Terminal Master Hub** es una aplicación Android todo-en-uno que integra un emulador de terminal Linux con entorno aislado, un IDE de Python con renderizado gráfico, y herramientas profesionales de flasheo para dispositivos Android vía USB OTG (ADB/Fastboot universal, Samsung Odin3 y Xiaomi MiTool).

Desarrollada por **Michael Antonio Rodriguez Condega** — *Ingeniero de Software Senior y Arquitecto de Sistemas Android*.

---

## 📱 Capturas

> *Próximamente: capturas de pantalla de cada sesión.*

---

## 🚀 Características Principales

### 🖥️ Sesión 1 — Terminal & Python IDE
- **Emulador de terminal Linux** con fuente monoespaciada (`Typeface.MONOSPACE`)
- **Banner de bienvenida dinámico** que se adapta automáticamente al ancho de la pantalla
- **Entorno Linux aislado (PRoot/Bootstrap)**: instala paquetes con `apt`, ejecuta scripts `.sh` sin necesidad de **root**
- **Selector de salida Python**: al ejecutar un script `.py`, elige entre:
  - ✅ Terminal (salida de texto)
  - ✅ Ventana Gráfica (renderizado en WebView con matplotlib, PIL, seaborn, plotly, etc.)
- **Editor de código embebido** para crear y modificar archivos `.py` y `.sh`
- **Historial de comandos** navegable con flechas
- **Detección de root** multinivel compatible con Magisk, KernelSU, APatch y SuperSU

### 🔌 Sesión 2 — USB Tools (ADB + Fastboot)

#### 📱 Sub-sesión 2A — ADB Shell Interactivo
- **Consola ADB nativa** sobre USB OTG sin dependencias externas
- Comandos: `adb devices`, `adb shell`, `adb push`, `adb pull`
- **Shell interactivo**: ejecuta comandos bash directamente en el dispositivo conectado
- **Transferencia de archivos**: envía y recibe archivos entre dispositivos
- Depuración remota y acceso a sistema de archivos del dispositivo

#### ⚡ Sub-sesión 2B — Fastboot (Bootloader & Flasheo)
- **Cliente Fastboot completo**: `devices`, `flash`, `reboot`, `oem`, `getvar`
- **Botones rápidos**: devices, getvar, flash, oem unlock/lock, reboot (con opciones)
- **Flasheo de particiones** con transferencia optimizada (chunks de 1 MB)
- **Desbloqueo de bootloader** (`flashing unlock`, `oem unlock`)
- Compatible con: **Xiaomi, POCO, Redmi, Samsung, Motorola, Google Pixel, OnePlus, Oppo, Huawei, LG, Sony, ASUS, HTC, Lenovo, ZTE** y más

### 🇸🇲 Sesión 3 — Samsung Odin3
- **Protocolo Odin3 nativo**: comunicación directa con Samsung en **Download Mode**
- **Flashing de firmware**: soporta archivos `.tar` y `.tar.md5`
- **Verificación MD5** integrada para validar integridad de firmware
- **Auto-reboot** y **wipe data** seleccionables
- **Optimización de memoria RAM**: procesa archivos de varios GB en chunks de 512 KB para evitar OOM (Out Of Memory)
- Basado en el análisis del protocolo Odin3 y herramientas como [ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool.git)

### 🇨🇳 Sesión 4 — Xiaomi MiTool
- **Selector de ROM Fastboot** (formato `.tgz`) desde el almacenamiento interno
- **Extracción inteligente**: descomprime la ROM en una carpeta temporal con Apache Commons Compress
- **Parser de `flash_all.sh`**: lee y traduce el script bash a comandos fastboot ejecutables (sin necesidad de ejecutar bash)
- **Flasheo automático**: ordena y ejecuta particiones secuencialmente
- Soporta: `flash_all.sh`, `flash_all_lock.sh`, `flash_all_except_data_storage.sh`
- Basado en el análisis de [MiTool](https://github.com/offici5l/MiTool.git)

### 📂 Explorador de Archivos Visual
- **Navegación gráfica** del sistema de archivos con lista en `AlertDialog`
- **Modo Root**: navega desde la raíz `/` del sistema
- **Modo No-Root**: navega desde `/storage/emulated/0/`
- Abre scripts `.py` con selector de salida (Terminal / Ventana Gráfica)
- Ejecuta scripts `.sh` directamente desde el explorador
- Ordena archivos: directorios primero, luego archivos

---

## 🔧 Permisos Android

La aplicación solicita los siguientes permisos:

| Permiso | Propósito |
|---------|-----------|
| `USB_PERMISSION` + `android.hardware.usb.host` | Conexión OTG para ADB/Fastboot/Odin3 |
| `MANAGE_EXTERNAL_STORAGE` | Acceso completo al almacenamiento para ROMs y scripts |
| `SYSTEM_ALERT_WINDOW` | Ventana flotante para capturar salida gráfica de Python |
| `FOREGROUND_SERVICE` | Servicio en primer plano durante flasheos |
| `POST_NOTIFICATIONS` | Notificaciones de progreso |
| `INTERNET` + `ACCESS_NETWORK_STATE` | Comunicación de red y APIs |

---

## 📲 Instalación

### Descargar APK

1. Ve a la sección **[Releases](https://github.com/MichaelARC-NI/TerminalMasterHub/releases)** de este repositorio
2. Descarga el archivo `TerminalMasterHub-v1.1-debug.apk` (o la versión más reciente)
3. Ábrelo desde el gestor de archivos
4. Concede permiso "Instalar apps desconocidas" si lo solicita
5. Toca **Instalar**

> **Nota**: Si tu dispositivo bloquea la instalación (HyperOS, MIUI, ColorOS, OneUI), usa ADB:
> ```bash
> adb install TerminalMasterHub-v1.1-debug.apk
> ```

### Compilar desde código fuente

```bash
git clone https://github.com/MichaelARC-NI/TerminalMasterHub.git
cd TerminalMasterHub
chmod +x gradlew
export ANDROID_HOME=/ruta/a/android-sdk
./gradlew assembleDebug
```

APK generado en: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🛠 Requisitos Técnicos

| Especificación | Valor |
|---------------|-------|
| **Min SDK** | 26 (Android 8.0 Oreo) |
| **Target SDK** | 35 (Android 15+) |
| **Compile SDK** | 35 |
| **Lenguaje** | Kotlin 100% |
| **Arquitectura** | MVVM + Fragmentos + ViewPager2 |
| **Build System** | Gradle KTS + AGP 8.7.3 |
| **JDK** | 17 |
| **Probada en** | Android 13, Android 16 |

### Dependencias principales

| Librería | Versión | Propósito |
|----------|---------|-----------|
| `androidx.core:core-ktx` | 1.15.0 | Core Android |
| `com.google.android.material:material` | 1.12.0 | Material Design 3 |
| `androidx.viewpager2:viewpager2` | 1.1.0 | Navegación por pestañas |
| `androidx.navigation:navigation-fragment-ktx` | 2.8.8 | Navegación |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Corrutinas asíncronas |
| `org.apache.commons:commons-compress` | 1.27.1 | Compresión TAR/TGZ |
| `com.github.topjohnwu.libsu:core` | 5.2.0 | API de root (libsu) |
| `com.google.code.gson:gson` | 2.11.0 | Serialización JSON |
| `androidx.webkit:webkit` | 1.12.1 | WebView para gráficos Python |

---

## 🧠 Arquitectura del Código

```
TerminalMasterHub/
├── app/
│   ├── src/main/java/com/terminalmasterhub/
│   │   ├── MainActivity.kt                  ← Activity principal con Bottom Nav
│   │   ├── TerminalMasterHubApp.kt          ← Application class
│   │   ├── core/
│   │   │   ├── adb/
│   │   │   │   ├── AdbClient.kt             ← Cliente ADB nativo (protocolo USB)
│   │   │   │   └── FastbootClient.kt         ← Cliente Fastboot nativo
│   │   │   ├── file/
│   │   │   │   └── FileManager.kt           ← Utilidades de archivos, MD5, TAR
│   │   │   ├── mitool/
│   │   │   │   ├── MiToolParser.kt           ← Parser de flash_all.sh
│   │   │   │   └── TgzExtractor.kt           ← Extractor de .tgz
│   │   │   ├── odin/
│   │   │   │   └── OdinProtocol.kt           ← Protocolo Odin3 para Samsung
│   │   │   ├── permissions/
│   │   │   │   └── PermissionManager.kt      ← Gestión de permisos runtime
│   │   │   ├── root/
│   │   │   │   ├── RootChecker.kt            ← Detección de root nativa
│   │   │   │   └── BootstrapManager.kt       ← Entorno Linux PREFIX (usr/)
│   │   │   └── usb/
│   │   │       ├── UsbManagerCore.kt         ← Núcleo USB OTG
│   │   │       ├── UsbBroadcastReceiver.kt   ← Receptor de eventos USB
│   │   │       └── FlashForegroundService.kt ← Servicio de flasheo en bg
│   │   ├── data/
│   │   │   └── TerminalHistory.kt            ← Historial de terminal
│   │   └── ui/
│   │       ├── SessionPagerAdapter.kt         ← Adaptador ViewPager2
│   │       ├── terminal/
│   │       │   ├── TerminalFragment.kt        ← Terminal Linux + Python IDE
│   │       │   ├── PythonBridge.kt            ← Puente Python
│   │       │   └── PythonGraphActivity.kt     ← Ventana gráfica Python
│   │       ├── fastboot/
│   │       │   ├── FastbootFragment.kt        ← Consola ADB/Fastboot
│   │       │   └── UsbDeviceActivity.kt       ← Diálogo de dispositivo USB
│   │       ├── samsung/
│   │       │   ├── SamsungOdinFragment.kt     ← Flasheo Samsung Odin3
│   │       │   └── SamsungUsbActivity.kt      ← Diálogo de Samsung USB
│   │       ├── xiaomi/
│   │       │   └── XiaomiFragment.kt          ← Flasheo Xiaomi MiTool
│   │       └── explorer/
│   │           └── FileExplorerDialog.kt      ← Explorador visual de archivos
│   └── src/main/res/
│       ├── layout/                           ← Layouts XML
│       ├── values/                           ← Strings, colores, temas
│       ├── xml/                              ← Filtros USB (device_filter)
│       └── drawable/                         ← Iconos y recursos gráficos
├── build.gradle.kts                          ← Gradle raíz
├── settings.gradle.kts                       ← Configuración de módulos
└── gradlew                                   ← Wrapper Gradle
```

---

## 🔗 Enlaces del Desarrollador

| Red | Enlace |
|-----|--------|
| 👤 **Nombre** | Michael Antonio Rodriguez Condega |
| 🐙 **GitHub** | [MichaelARC-NI](https://github.com/MichaelARC-NI) |
| ✉️ **Correo** | [androidmovil@proton.me](mailto:androidmovil@proton.me) |
| 💬 **Telegram** | [@Michael_Antonio_Rodriguez](https://t.me/Michael_Antonio_Rodriguez) |
| 📘 **Facebook** | [Michael Antonio Rodriguez](https://www.facebook.com/share/1D1pfVdbXE) |

---

## 📋 Notas Técnicas Importantes

### Root
- La app **funciona sin root** para todas las funciones básicas (terminal, Python, explorador de archivos no-root)
- Con **root**: activa el explorador de sistema completo y comandos avanzados
- La detección de root soporta: Magisk, KernelSU, APatch, SuperSU y `su` tradicional

### ADB/Fastboot
- El cliente ADB usa el protocolo nativo sobre USB OTG
- Requiere que el dispositivo destino tenga `ro.debuggable=1` o autorización previa para ADB
- Fastboot funciona en cualquier dispositivo en modo bootloader

### Samsung Odin3
- El protocolo Odin3 se ha implementado desde cero basándose en el análisis de herramientas como [ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool.git) y Heimdall
- Solo funciona con dispositivos Samsung en **Download Mode** (Vol- + Home + Power)

### Xiaomi MiTool
- El parser de MiTool traduce scripts `flash_all.sh` a comandos fastboot internos
- Basado en el análisis de [MiTool](https://github.com/offici5l/MiTool.git) (oficial)
- Soporta ROMs Fastboot en formato `.tgz`

### Python
- Python se ejecuta a través del intérprete del sistema (si está instalado) o del bootstrap Linux
- Para Python nativo embebido (Chaquopy), se requiere configuración adicional en `build.gradle.kts`
- La detección de gráficos se hace por palabras clave (matplotlib, PIL, seaborn, plotly, etc.)

---

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Por favor:

1. Haz fork del repositorio
2. Crea una rama para tu característica (`git checkout -b feature/nueva-caracteristica`)
3. Haz commit de tus cambios (`git commit -am 'Añade nueva característica'`)
4. Push a la rama (`git push origin feature/nueva-caracteristica`)
5. Abre un Pull Request

---

## 📄 Licencia

Este proyecto es de código abierto. Todos los derechos reservados © 2026 Michael Antonio Rodriguez Condega.

---

**Repositorio**: [https://github.com/MichaelARC-NI/TerminalMasterHub](https://github.com/MichaelARC-NI/TerminalMasterHub)  
**Reportar errores**: [Abrir issue](https://github.com/MichaelARC-NI/TerminalMasterHub/issues)
