# Instant Caption · Interpretación simultánea con IA gratuita

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [العربية](README.ar.md)

## Baja latencia · Modelos de lenguaje locales · Android

Convierte la voz en subtítulos traducidos en tiempo real con IA ejecutada en tu teléfono. Gratis, sin suscripción ni clave de una API de traducción en la nube. Las donaciones son voluntarias.

[**Descargar APK**](https://github.com/zmxhot/InstantCaption/releases/tag/v1.20.1-beta.1)

> Beta pública para Android 10+ y ARM64. El APK firmado disponible utiliza modelos de CPU. El código incluye integración NPU, pero el APK NPU completo aún no se publica: quedan detalles de licencias de dependencias binarias por aclarar. [Estado de distribución](../NPU-DISTRIBUTION.md).

### Funciones

- Reconocimiento de voz y traducción local después de descargar los modelos; no requiere Google Play Services.
- HY-MT2-1.8B para traducción multilingüe directa, o rutas OPUS-MT más pequeñas. Qwen/Gemma para NPU en la integración completa compatible.
- Micrófono o audio de aplicaciones que permitan la captura de Android.
- Subtítulos flotantes que se arrastran y ocultan en un borde; tamaño, transparencia, altura y texto original configurables.
- Listas independientes de modelos de voz y traducción, con descargas reanudables, importación y comprobación de integridad.
- Diez idiomas de interfaz; los idiomas de voz y destino dependen del modelo.

### Primer uso

1. Descarga e instala `instant-caption-1.20.1-beta.1-portable-release.apk`. No necesitas compilar.
2. Elige los idiomas de origen y destino. Descarga los modelos recomendados por Wi-Fi.
3. Para traducción directa con un modelo de lenguaje, elige HY-MT2-1.8B si el dispositivo lo permite: unos 1,13 GB, más el modelo de voz; se prefieren 6 GB de RAM o más.
4. Ejecuta la comprobación local, elige micrófono o audio del dispositivo y concede el permiso correspondiente. Después de preparar los modelos puedes trabajar sin conexión.

La baja latencia depende del teléfono, modelo y temperatura. Las mediciones NPU del README inglés no describen el APK CPU. La traducción puede contener errores. El modo de audio usa el permiso de compartir pantalla de Android para obtener audio, sin capturar imágenes ni vídeo; las llamadas y el contenido protegido pueden quedar excluidos.

[Compatibilidad](../COMPATIBILITY.md) · [Privacidad](../../PRIVACY.md) · [Compilar](../BUILD.md) · [Problemas](https://github.com/zmxhot/InstantCaption/issues) · [Donar voluntariamente](../SUPPORT.md)

Código de la aplicación: Apache-2.0. Los modelos y bibliotecas conservan sus propias licencias y restricciones. Ninguna función requiere una donación.
