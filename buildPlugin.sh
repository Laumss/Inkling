#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# buildPlugin.sh — Analyze Android Dependencies and Generate PluginConfig.json
# Bash equivalent of buildPlugin.ps1
# ============================================================

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

# ---------- Color helpers ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_color() {
    local msg="$1" color="${2:-NC}"
    case "$color" in
        Red)    echo -e "${RED}${msg}${NC}" ;;
        Green)  echo -e "${GREEN}${msg}${NC}" ;;
        Yellow) echo -e "${YELLOW}${msg}${NC}" ;;
        Blue)   echo -e "${BLUE}${msg}${NC}" ;;
        *)      echo "$msg" ;;
    esac
}

# ---------- Generate 16-char random string ----------
new_random_string() {
    local len="${1:-16}"
    cat /dev/urandom | tr -dc 'a-z0-9' | head -c "$len"
}

# ---------- Read package.json ----------
get_package_info() {
    local pkg_json="$PROJECT_ROOT/package.json"
    if [[ ! -f "$pkg_json" ]]; then
        print_color "package.json file not found" Red
        exit 1
    fi
    PKG_NAME=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('name',''))" "$pkg_json" 2>/dev/null) || {
        print_color "Failed to parse package.json" Red; exit 1
    }
    PKG_DESC=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('description',''))" "$pkg_json" 2>/dev/null) || PKG_DESC=""
    PKG_VERSION=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('version','0.0.1'))" "$pkg_json" 2>/dev/null) || PKG_VERSION="0.0.1"
}

# ---------- Create PluginConfig.json ----------
new_plugin_config() {
    local plugin_id="$1"
    local config_file="$PROJECT_ROOT/PluginConfig.json"
    print_color "Creating PluginConfig.json file..." Blue
    python3 -c "
import json, sys
config = {
    'name': sys.argv[1],
    'desc': sys.argv[2],
    'iconPath': '',
    'versionName': sys.argv[3],
    'versionCode': '1',
    'pluginID': sys.argv[4],
    'pluginKey': sys.argv[1],
    'jsMainPath': 'index'
}
with open(sys.argv[5], 'w', encoding='utf-8') as f:
    json.dump(config, f, indent=4, ensure_ascii=False)
" "$PKG_NAME" "$PKG_DESC" "$PKG_VERSION" "$plugin_id" "$config_file"
    print_color "PluginConfig.json file created: $config_file" Green
}

# ---------- Check if module name should be ignored ----------
is_ignored_module() {
    local name="$1"
    local lower
    lower=$(echo "$name" | tr '[:upper:]' '[:lower:]')
    [[ "$lower" == "react-native" ]] && return 0
    [[ "$lower" == "react" ]] && return 0
    [[ "$lower" == "sn-plugin-lib" ]] && return 0
    [[ "$lower" == @react-native* ]] && return 0
    [[ "$lower" == @react-navigation* ]] && return 0
    return 1
}

# ---------- Find ReactPackage implementations in a directory ----------
FOUND_PACKAGES=()

find_packages_in_directory() {
    local search_dir="$1"
    local result_file="$2"
    [[ ! -d "$search_dir" ]] && return

    local source_files=()
    while IFS= read -r -d '' f; do
        source_files+=("$f")
    done < <(find "$search_dir" -type f \( -name '*.java' -o -name '*.kt' \) -print0 2>/dev/null)

    for file in "${source_files[@]}"; do
        local content
        content=$(<"$file") || continue
        local is_kotlin=false
        [[ "$file" == *.kt ]] && is_kotlin=true

        local matches_class=false
        local class_name=""
        local package_name=""

        if $is_kotlin; then
            if echo "$content" | grep -qP 'class\s+([A-Za-z0-9_]+)\s*:\s*[^\{\n]*\b(ReactPackage|TurboReactPackage|BaseReactPackage|ViewManagerOnDemandReactPackage)\b'; then
                matches_class=true
                class_name=$(echo "$content" | grep -oP 'class\s+\K[A-Za-z0-9_]+(?=\s*:\s*[^\{\n]*\b(ReactPackage|TurboReactPackage|BaseReactPackage|ViewManagerOnDemandReactPackage)\b)' | head -1 || true)
            fi
            if echo "$content" | grep -qP '^\s*package\s+([^\s;]+)'; then
                package_name=$(echo "$content" | grep -oP '^\s*package\s+\K[^\s;]+' | head -1 || true)
            fi
        else
            if echo "$content" | grep -qP '(implements\s+(ReactPackage|ViewManagerOnDemandReactPackage)|extends\s+(ReactPackage|TurboReactPackage|BaseReactPackage))'; then
                matches_class=true
            fi
            if [[ -z "$class_name" ]]; then
                class_name=$(echo "$content" | grep -oP 'class\s+\K[A-Za-z0-9_]+' | head -1 || true)
            fi
            package_name=$(echo "$content" | grep -oP '^\s*package\s+\K[^;]+' | head -1 | tr -d ' ' || true)
        fi

        if $matches_class && [[ -n "$package_name" ]] && [[ -n "$class_name" ]]; then
            local full_class="${package_name}.${class_name}"
            print_color "  - Found ReactPackage implementation: $full_class" Green
            echo "  - $full_class" >> "$result_file"
            FOUND_PACKAGES+=("$full_class")
        fi
    done
}

# ---------- Find manually added packages from Application classes ----------
find_manual_react_packages_from_application() {
    local dirs_to_scan=(
        "$PROJECT_ROOT/android/app/src/main/java"
        "$PROJECT_ROOT/android/src/main/java"
        "$PROJECT_ROOT/app/android/src/main/java"
    )
    local found=()

    for dir in "${dirs_to_scan[@]}"; do
        [[ ! -d "$dir" ]] && continue
        while IFS= read -r -d '' f; do
            local text
            text=$(<"$f") || continue
            # Strip comments
            text=$(echo "$text" | sed 's|//.*$||g' | perl -0pe 's|/\*.*?\*/||gs')

            local pkg_name
            pkg_name=$(echo "$text" | grep -oP '^\s*package\s+\K[^\s;]+' | head -1 | tr -d ' ' || true)

            # Build import map
            unset _imports 2>/dev/null || true
            declare -A _imports=()
            while IFS= read -r line; do
                local fq short
                fq=$(echo "$line" | grep -oP '^\s*import\s+\K[^\s;]+' | tr -d ' ' || true)
                if [[ -n "$fq" ]]; then
                    short="${fq##*.}"
                    _imports["$short"]="$fq"
                fi
            done <<< "$text"

            _resolve_fqcn() {
                local name="$1"
                if [[ "$name" == *.* ]]; then echo "$name"; return; fi
                if [[ -n "${_imports[$name]:-}" ]]; then echo "${_imports[$name]}"; return; fi
                if [[ -n "$pkg_name" ]]; then echo "${pkg_name}.${name}"; return; fi
                echo "$name"
            }

            # Kotlin: add(ClassName())
            while IFS= read -r match; do
                [[ -z "$match" ]] && continue
                local fqcn
                fqcn=$(_resolve_fqcn "$match")
                [[ "$fqcn" == *Package ]] && found+=("$fqcn")
            done < <(echo "$text" | grep -oP '\badd\(\s*\K[A-Za-z0-9_.]+(?=\s*\()' 2>/dev/null || true)

            # Java: add(new ClassName())
            while IFS= read -r match; do
                [[ -z "$match" ]] && continue
                local fqcn
                fqcn=$(_resolve_fqcn "$match")
                [[ "$fqcn" == *Package ]] && found+=("$fqcn")
            done < <(echo "$text" | grep -oP '\b(?:packages\.)?add\(\s*new\s+\K[A-Za-z0-9_.]+(?=\s*\()' 2>/dev/null || true)

            unset imports
        done < <(find "$dir" -type f \( -name '*.java' -o -name '*.kt' \) -print0 2>/dev/null)
    done

    # Deduplicate
    if [[ ${#found[@]} -gt 0 ]]; then
        mapfile -t APPLICATION_PACKAGES < <(printf '%s\n' "${found[@]}" | sort -u)
    else
        APPLICATION_PACKAGES=()
    fi
    print_color "Manually added packages in Application: ${#APPLICATION_PACKAGES[@]}" Blue
    for pkg in "${APPLICATION_PACKAGES[@]}"; do
        print_color "  - $pkg" Green
    done
}

# ---------- Scan node_modules for native code ----------
scan_node_modules_native_code() {
    local node_modules="$PROJECT_ROOT/node_modules"
    NATIVE_MODS=()
    [[ ! -d "$node_modules" ]] && return

    for dir in "$node_modules"/*; do
        [[ ! -d "$dir" ]] && continue
        local base
        base=$(basename "$dir")

        if [[ "$base" == @* ]]; then
            for sub in "$dir"/*; do
                [[ ! -d "$sub" ]] && continue
                local mod_name="${base}/$(basename "$sub")"
                is_ignored_module "$mod_name" && continue
                local has_native=false
                for scan_dir in "$sub/android" "$sub/platforms/android" "$sub/platforms/android-native"; do
                    if [[ -d "$scan_dir" ]]; then
                        if find "$scan_dir" -type f \( -name '*.java' -o -name '*.kt' \) -print -quit 2>/dev/null | grep -q .; then
                            has_native=true; break
                        fi
                    fi
                done
                if $has_native; then
                    NATIVE_MODS+=("$mod_name")
                    print_color "Third-party module contains Android sources: $mod_name" Yellow
                fi
            done
        else
            is_ignored_module "$base" && continue
            local has_native=false
            for scan_dir in "$dir/android" "$dir/platforms/android" "$dir/platforms/android-native"; do
                if [[ -d "$scan_dir" ]]; then
                    if find "$scan_dir" -type f \( -name '*.java' -o -name '*.kt' \) -print -quit 2>/dev/null | grep -q .; then
                        has_native=true; break
                    fi
                fi
            done
            if $has_native; then
                NATIVE_MODS+=("$base")
                print_color "Third-party module contains Android sources: $base" Yellow
            fi
        fi
    done

    print_color "Third-party dependencies with Android sources: ${#NATIVE_MODS[@]}" Blue
}

# ---------- Parse autolinking PackageList.java ----------
get_react_packages_from_autolinking_source() {
    local src_file="$PROJECT_ROOT/android/app/build/generated/autolinking/src/main/java/com/facebook/react/PackageList.java"
    AUTOLINKING_PACKAGES=()
    if [[ ! -f "$src_file" ]]; then
        print_color "Autolinking PackageList.java not found: $src_file" Yellow
        return
    fi

    local text
    text=$(<"$src_file")

    # Build import map
    declare -A al_imports=()
    while IFS= read -r line; do
        local fq short
        fq=$(echo "$line" | grep -oP '^\s*import\s+\K[^\s;]+' | tr -d ' ' || true)
        if [[ -n "$fq" ]]; then
            short="${fq##*.}"
            al_imports["$short"]="$fq"
        fi
    done <<< "$text"

    # Extract new ClassName()
    while IFS= read -r name; do
        [[ -z "$name" ]] && continue
        local fqcn="$name"
        if [[ "$name" != *.* ]]; then
            fqcn="${al_imports[$name]:-$name}"
        fi
        [[ "$fqcn" == *Package ]] && AUTOLINKING_PACKAGES+=("$fqcn")
    done < <(echo "$text" | grep -oP 'new\s+\K[A-Za-z0-9_.]+(?=\s*\()' 2>/dev/null || true)

    # Deduplicate
    if [[ ${#AUTOLINKING_PACKAGES[@]} -gt 0 ]]; then
        mapfile -t AUTOLINKING_PACKAGES < <(printf '%s\n' "${AUTOLINKING_PACKAGES[@]}" | sort -u)
    fi

    print_color "Packages extracted from autolinking source: ${#AUTOLINKING_PACKAGES[@]}" Blue

    # Filter excluded packages
    local exclude=("com.facebook.react.shell.MainReactPackage" "com.ratta.supernote.pluginlib.PluginPackage")
    local filtered=()
    for pkg in "${AUTOLINKING_PACKAGES[@]}"; do
        local skip=false
        for ex in "${exclude[@]}"; do
            [[ "$pkg" == "$ex" ]] && skip=true && break
        done
        $skip || filtered+=("$pkg")
    done
    AUTOLINKING_PACKAGES=("${filtered[@]+"${filtered[@]}"}")
    print_color "Filtered package count: ${#AUTOLINKING_PACKAGES[@]}" Blue
    for p in "${AUTOLINKING_PACKAGES[@]}"; do
        print_color "  - kept: $p" Green
    done

    unset al_imports
}

# ---------- Update reactPackages in build/generated PluginConfig.json ----------
update_plugin_config_packages() {
    local build_config="$1"
    shift
    local packages=("$@")

    if [[ ${#packages[@]} -eq 0 ]]; then
        print_color "No ReactPackage implementations found, skipping PluginConfig.json update" Yellow
        return
    fi

    if [[ ! -f "$build_config" ]]; then
        local root_config="$PROJECT_ROOT/PluginConfig.json"
        if [[ -f "$root_config" ]]; then
            cp "$root_config" "$build_config"
            print_color "Copied PluginConfig.json from project root to build/generated folder" Blue
        else
            print_color "PluginConfig.json file not found" Red
            return
        fi
    fi

    print_color "Updating reactPackages field in build/generated PluginConfig.json..." Blue

    # Always prepend InklingPackages
    local all_pkgs=("com.supernote_quicktoolbar.InklingPackages" "${packages[@]}")

    python3 -c "
import json, sys
config_path = sys.argv[1]
pkgs = sys.argv[2:]
with open(config_path, 'r', encoding='utf-8') as f:
    config = json.load(f)
config['reactPackages'] = pkgs
with open(config_path, 'w', encoding='utf-8') as f:
    json.dump(config, f, indent=4, ensure_ascii=False)
" "$build_config" "${all_pkgs[@]}"

    print_color "PluginConfig.json in build/generated folder updated with reactPackages field" Green
}

# ---------- React Native bundle ----------
build_react_native_bundle() {
    local output_dir="$1"
    local bundle_output="$output_dir/${PKG_NAME}.bundle"

    print_color "Starting React Native bundling..." Blue
    print_color "Executing: npx react-native bundle ..." Yellow

    (cd "$PROJECT_ROOT" && npx react-native bundle \
        --entry-file index.js \
        --bundle-output "$bundle_output" \
        --platform android \
        --assets-dest "$output_dir" \
        --dev false)

    if [[ $? -eq 0 ]]; then
        print_color "React Native bundling successful" Green
        print_color "Bundle file generated: $bundle_output" Green
        return 0
    else
        print_color "React Native bundling failed" Red
        return 1
    fi
}

# ---------- Gradle APK build ----------
build_android_apk() {
    local require_check="${1:-false}"
    local build_config="$BUILD_GENERATED_DIR/PluginConfig.json"

    if [[ "$require_check" == "true" ]]; then
        if ! python3 -c "import json; c=json.load(open('$build_config')); assert 'reactPackages' in c" 2>/dev/null; then
            print_color "No reactPackages field in build/generated PluginConfig.json, skipping APK build" Yellow
            return 1
        fi
    fi

    print_color "Starting gradle build script to generate APK..." Blue

    local android_dir="$PROJECT_ROOT/android"
    if [[ ! -d "$android_dir" ]]; then
        print_color "Cannot find android directory" Red
        return 1
    fi

    if [[ -f "$android_dir/gradlew" ]]; then
        chmod +x "$android_dir/gradlew"
        sed -i 's/\r$//' "$android_dir/gradlew"
        print_color "Using gradlew to execute buildCustomApkDebug task..." Green
        (cd "$android_dir" && ./gradlew buildCustomApkDebug)
    elif command -v gradle &>/dev/null; then
        print_color "Using gradle to execute buildCustomApkDebug task..." Green
        (cd "$android_dir" && gradle buildCustomApkDebug)
    else
        print_color "Neither gradle nor gradlew found, cannot build APK" Red
        return 1
    fi

    local rc=$?
    if [[ $rc -eq 0 ]]; then
        print_color "APK build successful" Green
        return 0
    else
        print_color "APK build failed" Red
        return 1
    fi
}

# ---------- Copy APK and update nativeCodePackage ----------
copy_apk_and_update_config() {
    local apk_search="$PROJECT_ROOT/android/app/build/outputs/apk"
    local apk_path=""

    # Prefer custom APK
    apk_path=$(find "$apk_search" -name '*custom*.apk' -type f 2>/dev/null | head -1)
    if [[ -z "$apk_path" ]]; then
        apk_path=$(find "$apk_search" -name '*.apk' -type f 2>/dev/null | head -1)
    fi

    if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
        print_color "Generated APK file not found" Red
        return 1
    fi
    print_color "Found APK file: $apk_path" Green

    local target="$BUILD_GENERATED_DIR/app.npk"
    cp "$apk_path" "$target"
    print_color "APK file copied and renamed to build/generated folder: $target" Green

    local build_config="$BUILD_GENERATED_DIR/PluginConfig.json"
    if [[ ! -f "$build_config" ]]; then
        local root_config="$PROJECT_ROOT/PluginConfig.json"
        if [[ -f "$root_config" ]]; then
            cp "$root_config" "$build_config"
        else
            print_color "PluginConfig.json file not found" Red
            return 1
        fi
    fi

    python3 -c "
import json, sys
p = sys.argv[1]
with open(p, 'r', encoding='utf-8') as f:
    c = json.load(f)
c['nativeCodePackage'] = '/app.npk'
with open(p, 'w', encoding='utf-8') as f:
    json.dump(c, f, indent=4, ensure_ascii=False)
" "$build_config"

    print_color "PluginConfig.json updated with nativeCodePackage field: /app.npk" Green
    return 0
}

# ---------- Copy icon and update iconPath ----------
copy_icon_and_update_path() {
    local build_config="$BUILD_GENERATED_DIR/PluginConfig.json"
    print_color "Checking and copying icon file..." Blue

    local root_config="$PROJECT_ROOT/PluginConfig.json"
    local icon_path
    icon_path=$(python3 -c "import json; print(json.load(open('$root_config')).get('iconPath',''))" 2>/dev/null) || icon_path=""

    if [[ -z "$icon_path" ]]; then
        print_color "iconPath not set or empty in root PluginConfig.json" Yellow
        return
    fi

    print_color "Detected icon path: $icon_path" Yellow

    local source_icon
    if [[ "$icon_path" == /* ]]; then
        source_icon="$icon_path"
    else
        source_icon="$PROJECT_ROOT/$icon_path"
    fi

    if [[ -f "$source_icon" ]]; then
        local icon_filename
        icon_filename=$(basename "$source_icon")
        cp "$source_icon" "$BUILD_GENERATED_DIR/$icon_filename"
        print_color "Icon file copied to: $BUILD_GENERATED_DIR/$icon_filename" Green

        python3 -c "
import json, sys
p, icon = sys.argv[1], sys.argv[2]
with open(p, 'r', encoding='utf-8') as f:
    c = json.load(f)
c['iconPath'] = '/' + icon
with open(p, 'w', encoding='utf-8') as f:
    json.dump(c, f, indent=4, ensure_ascii=False)
" "$build_config" "$icon_filename"

        print_color "Updated iconPath in build/generated PluginConfig.json: /$icon_filename" Green
    else
        print_color "Icon file does not exist: $source_icon" Yellow
    fi
}

# ---------- Create zip and rename to .snplg ----------
create_snplg_package() {
    local source_dir="$1"
    local output_dir="$2"
    local project_name="$3"

    mkdir -p "$output_dir"

    if [[ ! -d "$source_dir" ]] || [[ -z "$(ls -A "$source_dir" 2>/dev/null)" ]]; then
        print_color "build/generated directory is empty or does not exist, cannot package" Red
        return 1
    fi

    local temp_zip="$output_dir/temp_package.zip"
    local snplg_file="$output_dir/${project_name}.snplg"

    # Remove old files if they exist
    rm -f "$temp_zip" "$snplg_file"

    print_color "Starting to package build/generated directory..." Blue
    python3 -c "
import zipfile, os, sys
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src):
        for f in files:
            fp = os.path.join(root, f)
            arcname = os.path.relpath(fp, src)
            zf.write(fp, arcname)
" "$source_dir" "$temp_zip"

    if [[ $? -ne 0 ]]; then
        print_color "Packaging failed, unable to create zip file" Red
        return 1
    fi

    mv "$temp_zip" "$snplg_file"
    print_color "Plugin package successfully generated: $snplg_file" Green

    local file_size
    file_size=$(du -h "$snplg_file" | cut -f1)
    print_color "File size: $file_size" Blue
    return 0
}

# ============================================================
# Main
# ============================================================
main() {
    print_color "Running on Linux (bash)" Blue
    print_color "Project root directory: $PROJECT_ROOT" Green

    # Step 1: Check build/generated directory
    print_color "=== Step 1: Check build/generated directory ===" Blue
    get_package_info
    BUILD_GENERATED_DIR="$PROJECT_ROOT/build/generated"

    if [[ -d "$BUILD_GENERATED_DIR" ]]; then
        print_color "Detected build/generated directory already exists: $BUILD_GENERATED_DIR" Yellow
    else
        mkdir -p "$BUILD_GENERATED_DIR"
        print_color "Created build/generated directory: $BUILD_GENERATED_DIR" Green
    fi

    # Step 2: React Native bundling
    print_color "=== Step 2: Execute React Native bundling ===" Blue
    if ! build_react_native_bundle "$BUILD_GENERATED_DIR"; then
        print_color "React Native bundling failed, script terminated" Red
        exit 1
    fi

    # Step 3: Check root PluginConfig.json
    print_color "=== Step 3: Check root directory PluginConfig.json ===" Blue
    local root_config="$PROJECT_ROOT/PluginConfig.json"

    if [[ -f "$root_config" ]]; then
        print_color "Detected root directory PluginConfig.json file already exists, skipping generation step" Yellow
    else
        # Step 4: Generate random pluginID
        print_color "=== Step 4: Generate random pluginID ===" Blue
        local plugin_id
        plugin_id=$(new_random_string 16)
        print_color "Generated pluginID: $plugin_id" Blue

        # Step 5: Generate PluginConfig.json
        print_color "=== Step 5: Generate root directory PluginConfig.json ===" Blue
        new_plugin_config "$plugin_id"
    fi

    # Step 6: Copy PluginConfig.json to build/generated and handle icon
    print_color "=== Step 6: Copy PluginConfig.json to build/generated folder and handle icon ===" Blue
    local build_config="$BUILD_GENERATED_DIR/PluginConfig.json"
    cp "$root_config" "$build_config"
    print_color "Copied root directory PluginConfig.json to build/generated folder" Green
    copy_icon_and_update_path

    # Step 7: Parse manually added packages from Application
    print_color "=== Step 7: Parse Application.getPackages (manually added packages) ===" Blue
    APPLICATION_PACKAGES=()
    find_manual_react_packages_from_application

    # Step 8: Scan node_modules for native code
    print_color "=== Step 8: Scan node_modules for Android sources ===" Blue
    NATIVE_MODS=()
    scan_node_modules_native_code

    # Step 9: Build condition check
    print_color "=== Step 9: Build condition check ===" Blue
    local should_build=false
    if [[ ${#APPLICATION_PACKAGES[@]} -gt 0 ]] || [[ ${#NATIVE_MODS[@]} -gt 0 ]]; then
        should_build=true
    fi

    if $should_build; then
        print_color "Build conditions met: project packages=${#APPLICATION_PACKAGES[@]}, third-party native modules=${#NATIVE_MODS[@]}" Green

        # Step 10: Gradle APK build
        print_color "=== Step 10: Invoke Gradle to build APK ===" Blue
        local build_success=false
        if build_android_apk "false"; then
            build_success=true
        fi

        # Step 11: Copy APK and update nativeCodePackage
        if $build_success; then
            print_color "=== Step 11: Copy APK and update nativeCodePackage ===" Blue
            if ! copy_apk_and_update_config; then
                print_color "Failed to copy APK or update configuration" Red
            fi
        else
            print_color "Gradle build failed, skipping APK copy and configuration update" Red
        fi

        # Step 12: Parse Autolinking PackageList.java
        print_color "=== Step 12: Parse Autolinking PackageList.java and merge lists ===" Blue
        AUTOLINKING_PACKAGES=()
        get_react_packages_from_autolinking_source

        # Merge and deduplicate
        local all_pkgs=()
        all_pkgs+=("${APPLICATION_PACKAGES[@]+"${APPLICATION_PACKAGES[@]}"}")
        all_pkgs+=("${AUTOLINKING_PACKAGES[@]+"${AUTOLINKING_PACKAGES[@]}"}")
        local dedup_pkgs=()
        if [[ ${#all_pkgs[@]} -gt 0 ]]; then
            mapfile -t dedup_pkgs < <(printf '%s\n' "${all_pkgs[@]}" | sort -u)
        fi

        # Step 13: Write reactPackages field
        print_color "=== Step 13: Write reactPackages field ===" Blue
        for p in "${dedup_pkgs[@]+"${dedup_pkgs[@]}"}"; do
            print_color "  - write: $p" Green
        done
        update_plugin_config_packages "$build_config" "${dedup_pkgs[@]+"${dedup_pkgs[@]}"}"
    else
        print_color "Build conditions not met; skipping steps 10-13 and proceeding to packaging" Yellow
    fi

    # Step 14: Package and generate .snplg
    print_color "=== Step 14: Package build/generated directory and generate .snplg file ===" Blue
    local build_outputs="$PROJECT_ROOT/build/outputs"
    create_snplg_package "$BUILD_GENERATED_DIR" "$build_outputs" "$PKG_NAME"
}

main "$@"
