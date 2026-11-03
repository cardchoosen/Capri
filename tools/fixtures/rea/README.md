# REA analysis fixture

This unsigned, non-installable test input contains one Java fee function and two string resources. It is inspected as data; the APK is never installed. The equivalent native function is compiled inside the dedicated device-test workspace.

The checked-in APK in `app/src/androidTest/assets/rea-fixture.apk` is built from this directory with Android SDK aapt2, JDK javac and d8: compile `Fixture.java` with Java 8 bytecode, convert the class to `classes.dex`, compile/link `res` with the manifest, then add the DEX to the linked APK. No external target or proprietary input is required.

Run the four real device cases through `python3 tools/verify.py --profile rea --device --approve-capri-install --cleanup-test`. First setup downloads shared analysis tools and can compile Ghidra; later runs reuse them. Runtime coverage is binding/device discovery only; this fixture does not authorize or prove target application attachment or injection.
