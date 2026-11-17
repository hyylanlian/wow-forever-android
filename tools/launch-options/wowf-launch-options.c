/*
 * Win32 helper for Battle.net's per-title launch-options channel, adapted from
 * waypoint-launcher (0BSD) and BnetTokenator. Writes WEB_TOKEN (DPAPI-protected),
 * REGION and LOCALE for World of Warcraft (program_id "WoW") so the game's own
 * SSO login reads them on startup. Also clears WEB_TOKEN for "Forget Login".
 *
 * Runs INSIDE the Wine prefix (same user/session as the game), so CryptProtectData
 * and the game's CryptUnprotectData share the same DPAPI master key.
 *
 * Invocation (argv, no CRT):
 *   wowf-launch-options.exe write <token> <region> <locale>
 *   wowf-launch-options.exe clear
 *   token  = <REGION>-<32 hex>-<account id>   (e.g. CN-0123...-1234567890)
 *   region = EU | US | KR | CN
 *   locale = 4 ASCII letters (e.g. zhCN) or empty
 *
 * Exit status 87 means "ok" (Wine can return 0 when its server disappears, so a
 * nonzero success code distinguishes a completed protocol run); the caller must
 * also see the exact "ok\n" acknowledgement on stdout.
 *
 * Build (no SDK headers needed — all Win32 prototypes are declared below and
 * resolved against Wine's own DLLs via the .def import libraries; a CRT is linked
 * only for argv access):
 *
 *   aarch64-w64-mingw32-gcc -Os wowf-launch-options.c kernel32.def crypt32.def advapi32.def \
 *       -o wowf-launch-options.exe
 */
typedef unsigned long DWORD;
typedef unsigned short WCHAR;
typedef void *HANDLE;
typedef struct { DWORD size; unsigned char *bytes; } BLOB;
#define DLL __declspec(dllimport)
DLL void *__stdcall LocalFree(void *);
DLL HANDLE __stdcall GetStdHandle(DWORD);
DLL int __stdcall WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
DLL int __stdcall CryptProtectData(BLOB *, const WCHAR *, BLOB *, void *, void *, DWORD, BLOB *);
DLL int __stdcall CryptUnprotectData(BLOB *, WCHAR **, BLOB *, void *, void *, DWORD, BLOB *);
DLL long __stdcall RegCreateKeyExW(HANDLE, const WCHAR *, DWORD, WCHAR *, DWORD, DWORD, void *, HANDLE *, DWORD *);
DLL long __stdcall RegOpenKeyExW(HANDLE, const WCHAR *, DWORD, DWORD, HANDLE *);
DLL long __stdcall RegSetValueExW(HANDLE, const WCHAR *, DWORD, DWORD, const unsigned char *, DWORD);
DLL long __stdcall RegDeleteValueW(HANDLE, const WCHAR *);
DLL long __stdcall RegCloseKey(HANDLE);

/* Battle.net's fixed DPAPI entropy (same in BnetTokenator and waypoint). */
static unsigned char entropyBytes[16] = {
    0xc8, 0x76, 0xf4, 0xae, 0x4c, 0x95, 0x2e, 0xfe,
    0xf2, 0xfa, 0x0f, 0x54, 0x19, 0xc0, 0x9c, 0x43
};

/* HKCU\Software\Blizzard Entertainment\Battle.net\Launch Options\WoW */
static const WCHAR keyPath[] =
    L"Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW";

static unsigned char encrypted[8192];

static DWORD length(const char *s) { DWORD n = 0; while (s[n]) n++; return n; }

static void clear(void *p, DWORD n) { volatile unsigned char *b = (unsigned char *)p; while (n--) *b++ = 0; }

static int equal(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }

/* Validates <AA>-<32 hex>-<digits> with a sensible length bound. */
static int ticket(const unsigned char *p, DWORD n) {
    if (n < 37 || n > 128 || p[2] != '-' || p[35] != '-') return 0;
    for (DWORD i = 0; i < 2; i++) if (p[i] < 'A' || p[i] > 'Z') return 0;
    for (DWORD i = 3; i < 35; i++)
        if (!((p[i] >= '0' && p[i] <= '9') || (p[i] >= 'a' && p[i] <= 'f') || (p[i] >= 'A' && p[i] <= 'F'))) return 0;
    for (DWORD i = 36; i < n; i++) if (p[i] < '0' || p[i] > '9') return 0;
    return 1;
}

/* The CRT provides main() and argv; we use WriteFile directly for the acknowledgement. */
static void ok(void) {
    static const char ack[] = "ok\n";
    DWORD written;
    WriteFile(GetStdHandle((DWORD)-11), ack, 3, &written, 0);
}

static void fail(const char *msg) {
    DWORD written;
    WriteFile(GetStdHandle((DWORD)-11), msg, length(msg), &written, 0);
}

int main(int argc, char **argv) {
    HANDLE hkcu = (HANDLE)(long long)(int)0x80000001; /* HKEY_CURRENT_USER */
    HANDLE key = 0;

    if (argc < 2) return 2;

    /* ---- clear: delete WEB_TOKEN only, keep REGION/LOCALE ---- */
    if (equal(argv[1], "clear")) {
        long result = RegOpenKeyExW(hkcu, keyPath, 0, 0x102, &key);
        if (result == 2) { ok(); return 87; } /* key absent: nothing to clear */
        if (!result) { result = RegDeleteValueW(key, L"WEB_TOKEN"); RegCloseKey(key); }
        if (result && result != 2) { fail("registry-failed\n"); return 7; }
        ok();
        return 87;
    }

    /* ---- write: token region locale ---- */
    if (!equal(argv[1], "write") || argc != 5) return 2;
    char *token = argv[2], *region = argv[3], *locale = argv[4];
    if (!ticket((unsigned char *)token, length(token))) return 2;
    if (!equal(region, "EU") && !equal(region, "US") && !equal(region, "KR") && !equal(region, "CN")) return 2;
    DWORD localeSize = length(locale);
    if (localeSize != 0 && localeSize != 4) return 2;
    for (DWORD i = 0; i < localeSize; i++)
        if (!((locale[i] >= 'A' && locale[i] <= 'Z') || (locale[i] >= 'a' && locale[i] <= 'z'))) return 2;

    BLOB entropy = { 16, entropyBytes }, plain = { 0, 0 }, protected = { 0, 0 };
    plain.size = length(token);
    plain.bytes = (unsigned char *)token;
    if (!CryptProtectData(&plain, 0, &entropy, 0, 0, 1, &protected)) { fail("protect-failed\n"); return 6; }

    /* Round-trip the blob to make sure the game will be able to decrypt it. */
    BLOB roundtrip = { 0, 0 };
    if (!CryptUnprotectData(&protected, 0, &entropy, 0, 0, 1, &roundtrip)) {
        clear(protected.bytes, protected.size); LocalFree(protected.bytes);
        fail("protect-failed\n"); return 6;
    }
    int valid = roundtrip.size == plain.size;
    if (valid) for (DWORD i = 0; i < plain.size; i++) if (roundtrip.bytes[i] != plain.bytes[i]) valid = 0;
    clear(roundtrip.bytes, roundtrip.size); LocalFree(roundtrip.bytes);
    if (!valid) { clear(protected.bytes, protected.size); LocalFree(protected.bytes); fail("protect-failed\n"); return 6; }

    /* 0x103 = KEY_QUERY_VALUE | KEY_SET_VALUE | KEY_WOW64_64KEY */
    long result = RegCreateKeyExW(hkcu, keyPath, 0, 0, 0, 0x103, 0, &key, 0);
    if (!result) {
        long removed = RegDeleteValueW(key, L"WEB_TOKEN"); /* invalidate any previous account first */
        if (removed && removed != 2) result = removed;
        WCHAR wideRegion[3] = { (WCHAR)region[0], (WCHAR)region[1], 0 };
        WCHAR wideLocale[5] = { 0, 0, 0, 0, 0 };
        for (DWORD i = 0; i < localeSize; i++) wideLocale[i] = (WCHAR)locale[i];
        if (!result) result = RegSetValueExW(key, L"REGION", 0, 1, (unsigned char *)wideRegion, sizeof(wideRegion));
        if (!result && localeSize) result = RegSetValueExW(key, L"LOCALE", 0, 1, (unsigned char *)wideLocale, sizeof(wideLocale));
        if (!result) result = RegSetValueExW(key, L"WEB_TOKEN", 0, 3, protected.bytes, protected.size);
        if (result) RegDeleteValueW(key, L"WEB_TOKEN"); /* don't leave a half-written state */
        RegCloseKey(key);
    }
    clear(protected.bytes, protected.size); LocalFree(protected.bytes);
    if (result) { fail("registry-failed\n"); return 7; }
    ok();
    return 87;
}
