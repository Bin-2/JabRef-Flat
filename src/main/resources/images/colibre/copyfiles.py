import os
import re
import shutil

# Remove inline comments that start after whitespace: " ... # comment" or " ... ; comment"
_INLINE_COMMENT_RE = re.compile(r'(?<!\\)\s[#;].*$')

def _strip_inline_comment(s: str) -> str:
    return _INLINE_COMMENT_RE.sub('', s)

def _read_text_any(path: str) -> str:
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return f.read()
    except UnicodeDecodeError:
        with open(path, 'r', encoding='latin-1') as f:
            return f.read()

def _unquote(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"'):
        return s[1:-1].strip()
    return s

def remove_unlisted_images(properties_file: str = "Icons.properties") -> None:
    """
    Remove PNG and SVG files in the current working directory
    that are NOT listed (right-hand side) in the properties file.

    - Only '=' splits; everything after '=' (before any inline comment) counts as filename.
    - Inline comments (# or ;) after whitespace are stripped.
    - Filenames are compared case-sensitively on Windows' file system behavior.
    - Skips missing files silently.
    """
    cwd = os.getcwd()
    if not os.path.isfile(properties_file):
        raise FileNotFoundError(f"Properties file not found: {properties_file}")

    # Build the allow-list from the properties file
    allowed = set()

    with open(properties_file, 'r', encoding='utf-8', errors='ignore') as f:
        for raw_line in f:
            stripped = raw_line.strip()
            if not stripped or stripped.startswith(('#', ';')):
                continue
            line = _strip_inline_comment(raw_line).strip()
            if not line or '=' not in line:
                continue
            _, rhs = line.split('=', 1)
            filename = _unquote(rhs).strip()
            if filename:
                # We only compare basenames because that’s what ends up in CWD
                allowed.add(os.path.basename(filename))

    # Iterate through PNG/SVG in CWD
    removed = 0
    kept = 0
    for entry in os.listdir(cwd):
        lower = entry.lower()
        if lower.endswith('.svg') or lower.endswith('.png'):
            if entry not in allowed:
                try:
                    os.remove(os.path.join(cwd, entry))
                    removed += 1
                    print(f"Removed: {entry}")
                except OSError as e:
                    print(f"Failed to remove {entry}: {e}")
            else:
                kept += 1

    print(f"\nDone. Removed {removed} unlisted image(s); kept {kept} listed image(s).")
    
def copy_icons_from_properties(source_root: str,
                               properties_file: str = "Icons.properties",
                               overwrite: bool = True) -> None:
    """
    Read a .properties file containing lines like 'new=newdoc.svg',
    and copy each referenced SVG/PNG (the part after '=') from *source_root* or any of its subfolders
    into the current working directory.

    Parameters
    ----------
    source_root : str
        Path to the root folder containing icons (searches recursively).
    properties_file : str
        Path to the Icons.properties file (default: 'Icons.properties' in current working directory).
    overwrite : bool
        If True (default), overwrite existing files in the destination; otherwise skip existing.
    """
    if not os.path.isfile(properties_file):
        raise FileNotFoundError(f"Properties file not found: {properties_file}")

    source_root = os.path.abspath(source_root)
    if not os.path.isdir(source_root):
        raise NotADirectoryError(f"Source folder not found: {source_root}")

    dest_folder = os.getcwd()

    # Build a map of filename (case-sensitive on Windows default) to full path found
    file_map = {}
    for dirpath, _, filenames in os.walk(source_root):
        for fname in filenames:
            file_map.setdefault(fname, os.path.join(dirpath, fname))

    copied = skipped_exist = missing = malformed = 0

    with open(properties_file, 'r', encoding='utf-8', errors='ignore') as f:
        for raw_line in f:
            stripped = raw_line.strip()
            if not stripped or stripped.startswith(('#', ';')):
                continue
            line = _strip_inline_comment(raw_line).strip()
            if not line or '=' not in line:
                malformed += 1
                continue

            _, rhs = line.split('=', 1)
            filename = _unquote(rhs).strip()
            if not filename:
                malformed += 1
                continue

            dest_name = os.path.basename(filename)
            dst_path = os.path.join(dest_folder, dest_name)

            src_path = file_map.get(dest_name)
            if src_path and os.path.isfile(src_path):
                if (not overwrite) and os.path.exists(dst_path):
                    skipped_exist += 1
                    print(f"Skip (exists): {dest_name}")
                    continue
                os.makedirs(dest_folder, exist_ok=True)
                shutil.copy2(src_path, dst_path)
                copied += 1
                print(f"Copied: {dest_name}")
            else:
                missing += 1
                print(f"Missing: {filename}")

    print(f"\nDone. Copied {copied} file(s); Skipped {skipped_exist}; Missing {missing}; Malformed {malformed}")


# --- Example usage (run in IDLE/Spyder on Windows) ---
# copy_icons_from_properties(r"C:\path\to\icons_folder", "Icons.properties", overwrite=True)

remove_unlisted_images("Icons.properties")
copy_icons_from_properties(r"B:\core-master\icon-themes\colibre_svg", "Icons.properties", overwrite=True)
