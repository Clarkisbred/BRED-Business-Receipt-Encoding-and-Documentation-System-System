package com.bred.rms.ai;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.List;

/** Stores a generic per-user credential in Windows Credential Manager. */
final class WindowsCredentialStore implements GroqCredentialStore {
    private static final String TARGET = "BRED/Ducky/Groq";
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    private final Advapi32 api;

    WindowsCredentialStore() {
        api = Native.load("Advapi32", Advapi32.class);
    }

    @Override
    public char[] read() {
        PointerByReference result = new PointerByReference();
        if (!api.CredReadW(new WString(TARGET), CRED_TYPE_GENERIC, 0, result)) {
            if (Native.getLastError() == ERROR_NOT_FOUND) return null;
            throw failure("read");
        }
        Pointer pointer = result.getValue();
        try {
            Credential credential = new Credential(pointer);
            credential.read();
            if (credential.CredentialBlobSize <= 0 || credential.CredentialBlob == null) return null;
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                char[] value = new char[decoded.remaining()];
                decoded.get(value);
                return value;
            } catch (CharacterCodingException error) {
                throw new IllegalStateException("The saved Groq credential could not be decoded.", error);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
                credential.CredentialBlob.clear(credential.CredentialBlobSize);
            }
        } finally {
            api.CredFree(pointer);
        }
    }

    @Override
    public void save(char[] apiKey) {
        if (apiKey == null || apiKey.length == 0) throw new IllegalArgumentException("Enter a Groq API key.");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(apiKey));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) java.util.Arrays.fill(encoded.array(), (byte) 0);
        Memory blob = new Memory(bytes.length);
        try {
            blob.write(0, bytes, 0, bytes.length);
            Credential credential = new Credential();
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = new WString(TARGET);
            credential.CredentialBlobSize = bytes.length;
            credential.CredentialBlob = blob;
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.UserName = new WString("BRED Ducky");
            credential.write();
            if (!api.CredWriteW(credential, 0)) throw failure("save");
        } finally {
            blob.clear();
            blob.close();
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void delete() {
        if (!api.CredDeleteW(new WString(TARGET), CRED_TYPE_GENERIC, 0)
                && Native.getLastError() != ERROR_NOT_FOUND) {
            throw failure("remove");
        }
    }

    private static IllegalStateException failure(String action) {
        return new IllegalStateException("Windows Credential Manager could not " + action + " the Groq credential.");
    }

    private interface Advapi32 extends StdCallLibrary {
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);
        boolean CredWriteW(Credential credential, int flags);
        boolean CredDeleteW(WString target, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias",
            "UserName"})
    public static class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public int LastWrittenLow;
        public int LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() { }

        public Credential(Pointer pointer) {
            super(pointer);
        }
    }
}
