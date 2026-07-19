# Compilar la extensión SIN Android Studio (todo desde el navegador)

No necesitas instalar nada. GitHub Actions compila el `.jar` por ti en la
nube cada vez que subes cambios. Solo usas el navegador.

## Paso 1: Crear el repo

1. Ve a https://github.com → **New repository**.
2. Nombre: `movaplus-extensions` (o el que quieras).
3. Público, con README. **Create repository**.

## Paso 2: Subir los archivos (arrastrando, sin git)

En la página del repo, botón **Add file > Upload files**, y sube
respetando estas rutas EXACTAS (GitHub te deja crear carpetas escribiendo
la ruta completa en el nombre del archivo al subir):

```
src/main/java/com/dex/streamhub/extensions/api/ExtensionProvider.java
src/main/java/com/dex/streamhub/extensions/providers/cuevana/CuevanaExtension.java
.github/workflows/build.yml
repo.json
```

Truco: cuando arrastras `ExtensionProvider.java`, en el campo de nombre de
archivo que aparece arriba, sobreescribe con la ruta completa
`src/main/java/com/dex/streamhub/extensions/api/ExtensionProvider.java` —
GitHub crea las carpetas solo. Repite para cada archivo.

Dale **Commit changes** al final.

## Paso 3: Ver cómo compila solo

1. Ve a la pestaña **Actions** de tu repo (arriba, junto a "Code").
2. Vas a ver un workflow corriendo ("Build extension") — tarda unos
   3-5 minutos la primera vez.
3. Si termina con un ✅ verde, funcionó. Si sale ❌ rojo, entra y mira el
   log — mándamelo y lo arreglamos.

## Paso 4: Conseguir el link del .jar

1. Cuando termine, ve a la pestaña **Releases** de tu repo (o a la derecha
   del código, "Releases").
2. Vas a ver una release nueva tipo "Cuevana3 v1" con el archivo
   `cuevana3-v1.jar` adjunto.
3. Clic derecho sobre ese archivo > **Copiar dirección del enlace**. Ese
   es tu `downloadUrl`.

## Paso 5: Actualizar repo.json con ese link

1. Abre `repo.json` en el repo, botón de lápiz (editar).
2. Pega el link copiado en `"downloadUrl"`.
3. Commit changes.

## Paso 6: Usarlo en la app

En `ExtensionsActivity` de tu app, "Explorar extensiones", pegas la URL
raw de `repo.json`:
```
https://raw.githubusercontent.com/TU_USUARIO/movaplus-extensions/main/repo.json
```

## Si más adelante cambias el código de la extensión

Solo edita `CuevanaExtension.java` desde el navegador (botón de lápiz),
commit, y el workflow vuelve a correr solo — te genera una release nueva
(`v2`, `v3`, ...) con un link nuevo. Actualizas `downloadUrl` en
`repo.json` con el link nuevo y listo, sin tocar nada más.
