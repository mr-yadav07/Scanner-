# Native runtime third-party notices

- PRoot is built from the DroidNative fork at commit `4ac94ecbb567608725e1d68d5ea617800a76c9c4`. Its upstream license is GPL-2.0; the full license text is included in `licenses/PROOT-COPYING.txt`. Corresponding source is available at https://github.com/DroidNative/proot/tree/4ac94ecbb567608725e1d68d5ea617800a76c9c4.
- talloc 2.5.0 is downloaded from Samba and built as a static dependency. The talloc license is LGPL-3.0-or-later; see the source archive.
- SQLite amalgamation 3.54.1 is downloaded from sqlite.org and built into PRoot. SQLite is public domain.
- The Android-specific talloc configuration and bionic replacement helper are adapted from DroidNativeApp's native-host build at commit `fa591010d6e33adbf5a79178b874fd4abdc58931`.

The app ships the PRoot and loader binaries in Android's extracted native-library directory. Linux board toolchains are installed separately into the app-private runtime directory.
