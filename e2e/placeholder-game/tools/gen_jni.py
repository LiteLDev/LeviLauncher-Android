#!/usr/bin/env python3
"""Generates the placeholder game's JNI exports from the launcher's `minecraft` module.

The real game resolves the `native` methods declared in the launcher's stub
com.mojang.minecraftpe / XAL / Xbox / FMOD classes. This script reads those Java
declarations and emits one exported `Java_...` function per method, so the placeholder
exposes exactly the same JNI surface. Each generated function records the call (class,
method and arguments) to logcat under the LeviPlaceholder tag and returns the documented
default for its return type. Methods with hand-written behaviour are listed in OVERRIDES
and implemented in src/main/cpp/game.cpp; generation fails if one of them no longer
exists in Java, so a renamed method cannot silently lose its implementation.

Usage: python e2e/placeholder-game/tools/gen_jni.py [--check]
"""
import argparse
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
JAVA_ROOT = os.path.join(ROOT, 'minecraft', 'src', 'main', 'java')
OUT_DIR = os.path.join(ROOT, 'e2e', 'placeholder-game', 'src', 'main', 'cpp', 'generated')

# Which game library provides the natives of each package. Packages not listed are backed
# by real libraries the launcher bundles (maesdk, libHttpClient, PlayFab) and are skipped.
LIBRARIES = {
    'minecraftpe': ('com.mojang.minecraftpe', 'com.microsoft.xal', 'com.microsoft.xbox', 'com.microsoft.xboxtcui'),
    'fmod': ('org.fmod',),
}

# Natives implemented by hand in game.cpp: (binary class name, method name).
OVERRIDES = {
    ('com.mojang.minecraftpe.MainActivity', 'nativeResize'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeSuspend'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeStopThis'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeOnDestroy'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeShutdown'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeKeyHandler'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeSetTextboxText'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeSetHeadphonesConnected'),
    ('com.mojang.minecraftpe.MainActivity', 'nativeGetDeviceId'),
    ('com.mojang.minecraftpe.MainActivity', 'isPublishBuild'),
    ('com.mojang.minecraftpe.MainActivity', 'isTestInfrastructureDisabled'),
    ('com.mojang.minecraftpe.NetworkMonitor', 'nativeUpdateNetworkStatus'),
}

PRIMITIVES = {
    'void': ('void', 'V'), 'boolean': ('jboolean', 'Z'), 'byte': ('jbyte', 'B'),
    'char': ('jchar', 'C'), 'short': ('jshort', 'S'), 'int': ('jint', 'I'),
    'long': ('jlong', 'J'), 'float': ('jfloat', 'F'), 'double': ('jdouble', 'D'),
}
ARRAY_TYPES = {
    'boolean': 'jbooleanArray', 'byte': 'jbyteArray', 'char': 'jcharArray', 'short': 'jshortArray',
    'int': 'jintArray', 'long': 'jlongArray', 'float': 'jfloatArray', 'double': 'jdoubleArray',
}
DEFAULT_RETURNS = {
    'jboolean': 'JNI_FALSE', 'jbyte': '0', 'jchar': '0', 'jshort': '0', 'jint': '0',
    'jlong': '0', 'jfloat': '0.0f', 'jdouble': '0.0',
}


def strip_comments_and_literals(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            out.append(' ' * ((n if j < 0 else j + 2) - i))
            i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
        elif c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == '\\' else 1
            out.append(c + c)
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


CLASS_RE = re.compile(r'\b(class|interface|enum|record)\s+([A-Za-z_]\w*)')
NATIVE_RE = re.compile(r'\bnative\b')


def parse_file(path):
    src = strip_comments_and_literals(open(path, encoding='utf-8').read())
    pkg = re.search(r'\bpackage\s+([\w.]+)\s*;', src)
    pkg = pkg.group(1) if pkg else ''
    imports = dict((m.group(1).split('.')[-1], m.group(1))
                   for m in re.finditer(r'\bimport\s+([\w.]+)\s*;', src))
    methods, stack, pending, depth, i = [], [], None, 0, 0
    stmt_start = 0
    while i < len(src):
        c = src[i]
        m = CLASS_RE.match(src, i) if (c.isalpha() and (i == 0 or not (src[i - 1].isalnum() or src[i - 1] in '_.'))) else None
        if m and not src[i - 1:i] == '.':
            pending = m.group(2)
            i = m.end()
            continue
        if c == '{':
            depth += 1
            if pending:
                stack.append((pending, depth))
                pending = None
            stmt_start = i + 1
        elif c == '}':
            if stack and stack[-1][1] == depth:
                stack.pop()
            depth -= 1
            stmt_start = i + 1
        elif c == ';':
            stmt = src[stmt_start:i]
            stmt_start = i + 1
            if stack and stack[-1][1] == depth and NATIVE_RE.search(stmt) and '(' in stmt:
                methods.append(parse_native(stmt, pkg, [s[0] for s in stack], imports, path))
        i += 1
    return methods


def parse_native(stmt, pkg, classes, imports, path):
    stmt = re.sub(r'@\w+(\([^)]*\))?', ' ', stmt)
    stmt = re.sub(r'\bthrows\b[^)]*$', '', stmt.strip())
    head, params = stmt.split('(', 1)
    params = params.rsplit(')', 1)[0]
    words = head.split()
    name, ret = words[-1], words[-2]
    static = 'static' in words[:-2]
    args = []
    for p in [p.strip() for p in params.split(',') if p.strip()]:
        parts = [w for w in p.replace('...', '[]').split() if w != 'final']
        typ = parts[0] + ''.join(w for w in parts[1:-1] if w.startswith('['))
        if parts[-1].endswith('[]'):
            typ += '[]'
        args.append(typ)
    binary = (pkg + '.' if pkg else '') + '$'.join(classes)
    return {'class': binary, 'pkg': pkg, 'name': name, 'ret': ret, 'args': args,
            'static': static, 'imports': imports, 'file': path}


def resolve_class(simple, method):
    base = simple.split('<')[0]
    if '.' in base:
        return base
    if base in method['imports']:
        return method['imports'][base]
    if base in ('String', 'Object', 'Class', 'Throwable', 'Runnable', 'Integer', 'Long', 'Boolean'):
        return 'java.lang.' + base
    return method['pkg'] + '.' + base


def jni_type(typ):
    if typ in PRIMITIVES:
        return PRIMITIVES[typ][0]
    if typ.endswith('[]'):
        elem = typ[:-2]
        return ARRAY_TYPES.get(elem, 'jobjectArray') if not elem.endswith('[]') else 'jobjectArray'
    if typ == 'String':
        return 'jstring'
    return 'jobject'


def descriptor(typ, method):
    dims = typ.count('[]')
    base = typ.replace('[]', '')
    if base in PRIMITIVES:
        return '[' * dims + PRIMITIVES[base][1]
    return '[' * dims + 'L' + resolve_class(base, method).replace('.', '/') + ';'


def mangle(s):
    out = []
    for ch in s:
        if ch == '.' or ch == '/':
            out.append('_')
        elif ch == '_':
            out.append('_1')
        elif ch == ';':
            out.append('_2')
        elif ch == '[':
            out.append('_3')
        elif ch == '$':
            out.append('_00024')
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            out.append('_0%04x' % ord(ch))
    return ''.join(out)


def symbol(method, overloaded):
    sym = 'Java_' + mangle(method['class']) + '_' + mangle(method['name'])
    if overloaded:
        sym += '__' + mangle(''.join(descriptor(a, method) for a in method['args']))
    return sym


def log_arg(typ, var):
    j = jni_type(typ)
    if j == 'jstring':
        return f'log.str({var});'
    if j in ('jboolean',):
        return f'log.boolean({var});'
    if j in ('jfloat', 'jdouble'):
        return f'log.real({var});'
    if j in DEFAULT_RETURNS:
        return f'log.integer(static_cast<long long>({var}));'
    return f'log.object({var});'


def default_return(method):
    j = jni_type(method['ret'])
    if j == 'void':
        return None
    if j == 'jstring':
        return 'return env->NewStringUTF("");'
    if j in DEFAULT_RETURNS:
        return f'return {DEFAULT_RETURNS[j]};'
    return 'return nullptr;'


def generate(library, methods):
    counts = {}
    for m in methods:
        counts[(m['class'], m['name'])] = counts.get((m['class'], m['name']), 0) + 1
    lines = [
        '// GENERATED by e2e/placeholder-game/tools/gen_jni.py from the minecraft module. Do not edit.',
        f'// Library: lib{library}.so, {len(methods)} native methods.',
        '#include <jni.h>',
        '#include "../jni_log.h"',
        '',
        'extern "C" {',
    ]
    for m in sorted(methods, key=lambda m: (m['class'], m['name'], m['args'])):
        overloaded = counts[(m['class'], m['name'])] > 1
        sym = symbol(m, overloaded)
        self_type = 'jclass' if m['static'] else 'jobject'
        params = ['JNIEnv* env', f'{self_type} /*self*/'] + [f'{jni_type(a)} a{i}' for i, a in enumerate(m['args'])]
        ret = jni_type(m['ret'])
        lines.append('')
        lines.append(f'JNIEXPORT {ret} JNICALL {sym}({", ".join(params)}) {{')
        lines.append(f'    ph::JniCall log(env, "{m["class"]}", "{m["name"]}");')
        for i, a in enumerate(m['args']):
            lines.append(f'    {log_arg(a, f"a{i}")}')
        lines.append('    log.emit();')
        r = default_return(m)
        if r:
            lines.append(f'    {r}')
        lines.append('}')
    lines += ['', '}  // extern "C"', '']
    return '\n'.join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true', help='fail if the generated files are out of date')
    args = ap.parse_args()

    found = []
    for dirpath, _, files in os.walk(JAVA_ROOT):
        for f in files:
            if f.endswith('.java'):
                found += parse_file(os.path.join(dirpath, f))

    by_lib = {lib: [] for lib in LIBRARIES}
    seen_overrides = set()
    for m in found:
        key = (m['class'], m['name'])
        lib = next((l for l, pkgs in LIBRARIES.items()
                    if any(m['class'] == p or m['class'].startswith(p + '.') for p in pkgs)), None)
        if lib is None:
            continue
        if key in OVERRIDES:
            seen_overrides.add(key)
            continue
        by_lib[lib].append(m)

    missing = OVERRIDES - seen_overrides
    if missing:
        sys.exit('Overridden natives no longer declared in Java: ' + ', '.join(f'{c}.{n}' for c, n in sorted(missing)))

    stale = []
    os.makedirs(OUT_DIR, exist_ok=True)
    for lib, methods in by_lib.items():
        path = os.path.join(OUT_DIR, f'jni_{lib}.cpp')
        text = generate(lib, methods)
        current = open(path, encoding='utf-8').read() if os.path.exists(path) else None
        if current != text:
            if args.check:
                stale.append(path)
            else:
                with open(path, 'w', encoding='utf-8', newline='\n') as fh:
                    fh.write(text)
        print(f'lib{lib}.so: {len(methods)} generated + {sum(1 for c, _ in seen_overrides if lib == "minecraftpe")} hand-written natives'
              if lib == 'minecraftpe' else f'lib{lib}.so: {len(methods)} generated natives')
    if stale:
        sys.exit('Generated JNI files are out of date; run e2e/placeholder-game/tools/gen_jni.py: ' + ', '.join(stale))


if __name__ == '__main__':
    main()
