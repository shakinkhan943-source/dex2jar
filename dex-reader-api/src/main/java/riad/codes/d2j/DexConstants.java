/*
 * Copyright (c) 2009-2012 Panxiaobo
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package riad.codes.d2j;

/**
 * constants in dex file
 * 
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 * @version $Rev$
 */
public interface DexConstants {

    int ACC_PUBLIC = 0x0001; // class, field, method
    int ACC_PRIVATE = 0x0002; // class, field, method
    int ACC_PROTECTED = 0x0004; // class, field, method
    int ACC_STATIC = 0x0008; // field, method
    int ACC_FINAL = 0x0010; // class, field, method
    // int ACC_SUPER = 0x0020; // class
    int ACC_SYNCHRONIZED = 0x0020; // method
    int ACC_VOLATILE = 0x0040; // field
    int ACC_BRIDGE = 0x0040; // method
    int ACC_VARARGS = 0x0080; // method
    int ACC_TRANSIENT = 0x0080; // field
    int ACC_NATIVE = 0x0100; // method
    int ACC_INTERFACE = 0x0200; // class
    int ACC_ABSTRACT = 0x0400; // class, method
    int ACC_STRICT = 0x0800; // method
    int ACC_SYNTHETIC = 0x1000; // class, field, method
    int ACC_ANNOTATION = 0x2000; // class
    int ACC_ENUM = 0x4000; // class(?) field inner
    int ACC_CONSTRUCTOR = 0x10000;// constructor method (class or instance initializer)
    int ACC_DECLARED_SYNCHRONIZED = 0x20000;

    int ACC_VISIBILITY_FLAGS = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED;
    int ACC_DEX_HIDDEN_BIT = 0x00000020; // field, method (not native)
    int ACC_DEX_HIDDEN_BIT_NATIVE = 0x00000200; // method (native)

    String ANNOTATION_DEFAULT_TYPE = "Ldalvik/annotation/AnnotationDefault;";
    String ANNOTATION_SIGNATURE_TYPE = "Ldalvik/annotation/Signature;";
    String ANNOTATION_THROWS_TYPE = "Ldalvik/annotation/Throws;";
    String ANNOTATION_ENCLOSING_CLASS_TYPE = "Ldalvik/annotation/EnclosingClass;";
    String ANNOTATION_ENCLOSING_METHOD_TYPE = "Ldalvik/annotation/EnclosingMethod;";
    String ANNOTATION_INNER_CLASS_TYPE = "Ldalvik/annotation/InnerClass;";
    String ANNOTATION_MEMBER_CLASSES_TYPE = "Ldalvik/annotation/MemberClasses;";

    /**
     * Recognized DEX versions. Per ART's standard_dex_file.cc: 036 was skipped
     * (Dalvik bug); valid versions are 035 and 037-041. DEX 041 (Android V/15+)
     * extends the header from 0x70 to 0x78 bytes with two little-endian uints -
     * container_size (total physical container size) and header_offset (this
     * logical DEX's offset in the container) - and makes all header/offset fields
     * relative to the physical container start. For a standalone .dex (the layout
     * found in an APK's classes*.dex) header_offset is 0, so container-relative
     * offsets equal file-relative offsets and this reader parses them directly.
     * There is no DEX 042+ in ART today.
     */
    int DEX_035 = 0x00303335;
    @Deprecated
    int DEX_036 = 0x00303336;
    int DEX_037 = 0x00303337;
    int DEX_038 = 0x00303338;
    int DEX_039 = 0x00303339;
    int DEX_040 = 0x00303430;
    int DEX_041 = 0x00303431;

    /** Lowest recognized + supported version (legacy minimum accepted by readers). */
    int MIN_VERSION = DEX_035;

    /**
     * Highest DEX version whose format this reader implements. Standard 0x70
     * layouts through 040, plus the 0x78-header DEX 041 container layout for
     * standalone (header_offset == 0) files. This value is a little-endian int
     * as stored in the magic word (e.g. '0x00303431' = "041").
     */
    int MAX_SUPPORTED_VERSION = DEX_041;

    /** Highest version known to this build at all (may exceed MAX_SUPPORTED). */
    int MAX_RECOGNIZED_VERSION = DEX_041;

    /** Kept for compatibility with older callers; identical to [MAX_SUPPORTED_VERSION]. */
    @Deprecated
    int MAX_KNOWN_VERSION = MAX_SUPPORTED_VERSION;

    /**
     * Human-friendly description of the magic/version encoding of DEX files.
     * The magic is "dex\n039\0" (8 bytes), and the version is encoded as three
     * decimal digits in the magic bytes.
     */
    static String versionMagicDescription() {
        return "dex\\n039\\0 (8 bytes; version encoded as three decimal digits)";
    }

    static int toMiniAndroidApiLevel(int dexVersion) {
        if (dexVersion == DEX_035 || dexVersion == DEX_036) {
            return 0;
        }
        if (dexVersion == DEX_037) {
            return 24;
        }
        if (dexVersion == DEX_038) {
            return 26;
        }
        if (dexVersion == DEX_039) {
            return 28;
        }
        if (dexVersion == DEX_041) {
            return 35; // Android 15
        }
        return 29; // 040 = Android 10 (API 29)
    }

    /** Whether this reader treats the given version as in the recognized range. */
    static boolean isRecognized(int dexVersion) {
        return dexVersion == DEX_036 || (MIN_VERSION <= dexVersion && dexVersion <= MAX_RECOGNIZED_VERSION);
    }

    /**
     * Formats this build can actually parse and convert: 035 and 037-041.
     */
    static boolean isSupported(int dexVersion) {
        return dexVersion == DEX_035 || (DEX_037 <= dexVersion && dexVersion <= MAX_SUPPORTED_VERSION);
    }
}
