/*
 * Win32 helper for Battle.net's per-title launch-options channel, adapted from
 * waypoint-launcher (0BSD) and BnetTokenator. Writes WEB_TOKEN (DPAPI-protected),
 * REGION and LOCALE for World of Warcraft (program_id "WoW") so the game's own
 * SSO login reads them on startup. Also clears WEB_TOKEN for "Forget Login".
 *
 * Runs INSIDE the Wine prefix (same user/session as the game), so CryptProtectData
 * and the game's CryptUnprotectData share the same DPAPI master key.
 *
 * No CRT, no SDK headers: the entry point is mainCRTStartup, every Win32 symbol is
 * declared below and resolved against Wine's DLLs via the .def import libraries.
 * Built with clang + lld-link:
 *
 *   clang --target=aarch64-pc-windows-msvc -nostdlib -ffreestanding -fuse-ld=lld \
 *     -Wl,/entry:mainCRTStartup \
 *     -Wl,/def:kernel32.def -Wl,/def:crypt32.def -Wl,/def:advapi32.def \
 *     wowf-launch-options.c -o wowf-launch-options-arm64.exe
 *
 * Input is a text file at C:\windows\temp\wowf-launch-options.txt (the launcher
 * writes it, then deletes it after the helper exits; the container launch path can't
 * pipe stdin or argv):
 *     write\n<token>\n<region>\n<locale>\n
 *     clear\n
 *   token  = <REGION>-<32 hex>-<account id>   (e.g. CN-0123...-1234567890)
 *   region = EU | US | KR | CN
 *   locale = 4 ASCII letters (e.g. zhCN) or empty
 *
 * Exit status 87 means "ok" (Wine can return 0 when its server disappears, so a
 * nonzero success code distinguishes a completed protocol run).
 */
typedef unsigned long DWORD;
typedef unsigned short WCHAR;
typedef void *HANDLE;
typedef struct { DWORD size; unsigned char *bytes; } BLOB;
#define DLL __declspec(dllimport)
#define GENERIC_READ 0x80000000L
#define OPEN_EXISTING 3
#define INVALID_HANDLE_VALUE ((HANDLE)(long long)-1)

DLL HANDLE __stdcall CreateFileA(const char *, DWORD, DWORD, void *, DWORD, DWORD, HANDLE);
DLL int __stdcall ReadFile(HANDLE, void *, DWORD, DWORD *, void *);
DLL int __stdcall WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
DLL HANDLE __stdcall GetStdHandle(DWORD);
DLL int __stdcall CloseHandle(HANDLE);
DLL void *__stdcall LocalFree(void *);
__declspec(noreturn) DLL void __stdcall ExitProcess(unsigned int);
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

static const char inputPath[] = "C:\\windows\\temp\\wowf-launch-options.txt";

static unsigned char input[512], encrypted[8192];

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

__declspec(noreturn) static void finish(unsigned int status, const char *message) {
    clear(input, sizeof(input));
    clear(encrypted, sizeof(encrypted));
    if (message) { DWORD written; WriteFile(GetStdHandle((DWORD)-11), message, length(message), &written, 0); }
    ExitProcess(status == 0 ? 87 : status);
}

static char *line(char **p, char *end) {
    char *start = *p;
    while (*p < end && **p != '\n') (*p)++;
    if (*p == end) return 0;
    *(*p)++ = 0;
    return start;
}

/* Reads the input frame file; returns the byte count (0 on failure). */
static DWORD read_input(void) {
    HANDLE f = CreateFileA(inputPath, GENERIC_READ, 1 /* FILE_SHARE_READ */, 0, OPEN_EXISTING, 0x80 /* FILE_ATTRIBUTE_NORMAL */, 0);
    if (f == INVALID_HANDLE_VALUE) return 0;
    DWORD total = 0, n = 0;
    while (total < sizeof(input) - 1) {
        if (!ReadFile(f, input + total, sizeof(input) - 1 - total, &n, 0) || !n) break;
        total += n;
    }
    CloseHandle(f);
    input[total] = 0;
    return total;
}

void mainCRTStartup(void) {
    DWORD count = read_input();
    if (!count) finish(2, "input-failed\n");
    for (DWORD i = 0; i < count; i++)
        if (input[i] != '\n' && (input[i] < 32 || input[i] > 126)) finish(2, "invalid-input\n");

    char *p = (char *)input, *end = p + count;
    char *mode = line(&p, end);
    if (!mode) finish(2, "invalid-input\n");

    HANDLE hkcu = (HANDLE)(long long)(int)0x80000001; /* HKEY_CURRENT_USER */
    HANDLE key = 0;

    /* ---- clear: delete WEB_TOKEN only, keep REGION/LOCALE ---- */
    if (equal(mode, "clear") && p == end) {
        long result = RegOpenKeyExW(hkcu, keyPath, 0, 0x102, &key);
        if (result == 2) finish(0, "ok\n"); /* key absent: nothing to clear */
        if (!result) { result = RegDeleteValueW(key, L"WEB_TOKEN"); RegCloseKey(key); }
        finish((result && result != 2) ? 7 : 0, (result && result != 2) ? "registry-failed\n" : "ok\n");
    }

    /* ---- write: token region locale ---- */
    if (!equal(mode, "write")) finish(2, "invalid-input\n");
    char *token = line(&p, end);
    char *region = line(&p, end);
    char *locale = line(&p, end);
    if (!token || !region || !locale || p != end || !ticket((unsigned char *)token, length(token)))
        finish(2, "invalid-input\n");
    if (!equal(region, "EU") && !equal(region, "US") && !equal(region, "KR") && !equal(region, "CN"))
        finish(2, "invalid-input\n");
    DWORD localeSize = length(locale);
    if (localeSize != 0 && localeSize != 4) finish(2, "invalid-input\n");
    for (DWORD i = 0; i < localeSize; i++)
        if (!((locale[i] >= 'A' && locale[i] <= 'Z') || (locale[i] >= 'a' && locale[i] <= 'z'))) finish(2, "invalid-input\n");

    BLOB entropy = { 16, entropyBytes }, plain = { 0, 0 }, protected = { 0, 0 };
    plain.size = length(token);
    plain.bytes = (unsigned char *)token;
    if (!CryptProtectData(&plain, 0, &entropy, 0, 0, 1, &protected)) finish(6, "protect-failed\n");

    /* Round-trip the blob to make sure the game will be able to decrypt it. */
    BLOB roundtrip = { 0, 0 };
    if (!CryptUnprotectData(&protected, 0, &entropy, 0, 0, 1, &roundtrip)) {
        clear(protected.bytes, protected.size); LocalFree(protected.bytes);
        finish(6, "protect-failed\n");
    }
    int valid = roundtrip.size == plain.size;
    if (valid) for (DWORD i = 0; i < plain.size; i++) if (roundtrip.bytes[i] != plain.bytes[i]) valid = 0;
    clear(roundtrip.bytes, roundtrip.size); LocalFree(roundtrip.bytes);
    if (!valid) { clear(protected.bytes, protected.size); LocalFree(protected.bytes); finish(6, "protect-failed\n"); }

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
    finish(result ? 7 : 0, result ? "registry-failed\n" : "ok\n");
}
