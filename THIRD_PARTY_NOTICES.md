# Third-party notices

CraftCode's original code and artwork are available under the [MIT License](LICENSE). Third-party libraries and artwork carry the terms below. In the release JAR, additional license texts are in `META-INF/licenses/craftcode/`. In the source tree, the same files are in [src/main/resources/META-INF/licenses/craftcode](src/main/resources/META-INF/licenses/craftcode).

## Libraries in the mod JAR

| Component | Version | License and attribution |
| --- | --- | --- |
| [SpruceUI](https://github.com/LambdAurora/SpruceUI/tree/26.3) | 12.0.0+26.3 | MIT. Copyright © 2020–2023 LambdAurora. The nested JAR retains the original `LICENSE_spruceui`. This distribution replaces the library icon, legacy menu background, separators, checkbox marks and toggle controls with original artwork in [gradle/spruceui-assets](gradle/spruceui-assets), licensed under this project's MIT License. |
| [Yumi Minecraft Foundation](https://github.com/YumiProject/yumi-minecraft-foundation-library) | 1.1.3+26.2 | Mozilla Public License 2.0. Yumi Project Minecraft Libraries Development Team and LambdAurora. |
| [Yumi Commons](https://github.com/YumiProject/yumi-commons) collections, core and event | 2.0.0 | Mozilla Public License 2.0. Yumi Project Development Team. These three JARs are included inside the Foundation JAR. |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys/tree/twelvemonkeys-3.15.3) | 3.15.3 | BSD 3-Clause. Harald Kuhr and contributors, including Brooss for VP8 code. See `twelvemonkeys.txt` and the additional credits below. The distributed modules are `imageio-webp`, `imageio-core`, `imageio-metadata`, `common-image`, `common-io` and `common-lang`. |

The Yumi libraries are distributed unmodified. Each nested JAR contains its [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/). The source code for these exact binaries is available from Maven Central under that license.

- [Yumi Minecraft Foundation 1.1.3+26.2 source](https://repo.maven.apache.org/maven2/dev/yumi/mc/core/yumi-mc-foundation/1.1.3+26.2/yumi-mc-foundation-1.1.3+26.2-sources.jar)
- [Yumi Commons collections 2.0.0 source](https://repo.maven.apache.org/maven2/dev/yumi/commons/yumi-commons-collections/2.0.0/yumi-commons-collections-2.0.0-sources.jar)
- [Yumi Commons core 2.0.0 source](https://repo.maven.apache.org/maven2/dev/yumi/commons/yumi-commons-core/2.0.0/yumi-commons-core-2.0.0-sources.jar)
- [Yumi Commons event 2.0.0 source](https://repo.maven.apache.org/maven2/dev/yumi/commons/yumi-commons-event/2.0.0/yumi-commons-event-2.0.0-sources.jar)

### Additional TwelveMonkeys credits

This product includes software developed by the Apache Software Foundation (http://www.apache.org/). TwelveMonkeys' `IndexImage` includes Apache Batik material under the Apache Software License, Version 1.1. See `twelvemonkeys-batik.txt`.

Parts of the WebP reader reference Google's libwebp. See `twelvemonkeys-libwebp.txt` for the copyright, license and additional patent rights. JPEG quality estimation adapts ImageMagick code. See `twelvemonkeys-imagemagick.txt`. [libwebp source](https://chromium.googlesource.com/webm/libwebp/) · [ImageMagick source](https://github.com/ImageMagick/ImageMagick)

The resampler credits Dale Schumacher's public-domain Graphics Gems example and Ray Gardener of Daylon Graphics Ltd. The little-endian stream classes credit public-domain code by Elliotte Rusty Harold. The color converter credits public-domain ImageJ code. The TIFF rational arithmetic credits Robert Sedgewick and Kevin Wayne, who gave permission to redistribute it under the BSD license. The image blending code credits CompuPhase. The source distribution also identifies the ClayRGB1998 color profile as public domain. [TwelveMonkeys source and component credits](https://github.com/haraldk/TwelveMonkeys/tree/twelvemonkeys-3.15.3)

## Icons

| Artwork | Credit |
| --- | --- |
| T3 mod icon | Pixel adaptation of the T3 monogram. T3 Code is copyright © 2026 T3 Tools Inc. See `t3-code.txt`. [T3 Code](https://github.com/pingdotgg/t3code) |
| Default ChatGPT provider icon | Shuqi Khor, Pixel Icons, MIT. See `pixel-icons.txt`. [Original icon](https://sqkhor.com/pixel-icons/chatgpt) |
| Claude Code, OpenCode, Antigravity and Cursor provider icons and the optional ChatGPT/Codex variants | Pixel adaptations of reference marks from Lobe Icons 1.106.0, copyright © 2023 LobeHub, MIT. See `lobe-icons.txt`. [Lobe Icons source](https://github.com/lobehub/lobe-icons/tree/c9236c6735763f2778e7ca759488f9a486450249/packages/static-svg/icons) |

Product names and logos identify their respective projects. Their owners retain their trademark rights.

## Development and runtime dependencies

The source distribution includes the Gradle Wrapper under Apache License 2.0. Its license is also in `gradle/wrapper/gradle-wrapper.jar` at `META-INF/LICENSE`. [Gradle source](https://github.com/gradle/gradle)

The player's installation and development tools supply the following components separately from the mod JAR.

| Component | Role | Upstream license |
| --- | --- | --- |
| Minecraft Java Edition | Game client and game assets | Mojang and Microsoft's [Minecraft EULA](https://www.minecraft.net/en-us/eula) |
| Fabric Loader and Fabric API | Mod loader and client APIs | Apache-2.0. [Fabric Loader](https://github.com/FabricMC/fabric-loader/blob/master/LICENSE) · [Fabric API](https://github.com/FabricMC/fabric-api/blob/26.3/LICENSE) |
| Gson | JSON serialization | [Apache-2.0](https://github.com/google/gson/blob/main/LICENSE) |
| LWJGL | Native Java bindings | [BSD-3-Clause](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md) |
| SDL | Native file picker | [Zlib](https://github.com/libsdl-org/SDL/blob/main/LICENSE.txt) |
| JetBrains Java Annotations | Compile-time annotations | [Apache-2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) |
| Gradle | Build system | [Apache-2.0](https://github.com/gradle/gradle/blob/master/LICENSE) |
| Fabric Loom | Gradle build plugin | [MIT](https://github.com/FabricMC/fabric-loom/blob/dev/1.19/LICENSE) |
| JUnit Jupiter and JUnit Platform | Test engine and launcher | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r5.11.4/LICENSE.md) |
