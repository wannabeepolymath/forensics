Metadata depends heavily on file type. Here’s a broad list of what metadata **can exist** (not every file has all of these).

# Generic File System Metadata

Exists because of the OS/filesystem.

* Filename
* File size
* File extension
* File path
* Created timestamp
* Modified timestamp
* Access timestamp
* Permissions (`rwx`)
* Owner / group
* Hidden flag
* Inode number
* File hashes (MD5/SHA256 etc.)
* Symlink info
* Extended attributes (xattrs)
* Filesystem type
* Compression flags
* Encryption flags

---

# Image Metadata (JPEG, PNG, HEIC, etc.)

* Width / height
* Resolution (DPI)
* Color space
* Bit depth
* Compression type
* Camera model
* Lens model
* ISO
* Aperture
* Exposure time
* Focal length
* Flash used
* GPS coordinates
* Device manufacturer
* Device serial number (sometimes)
* Orientation
* Thumbnail previews
* Editing software used
* Copyright
* Author
* Face detection tags
* AI-generated flags (sometimes)

---

# Video Metadata

* Duration
* Codec
* FPS
* Bitrate
* Resolution
* Audio codecs
* Subtitle tracks
* Creation date
* GPS
* Device used
* Rotation info
* Color profiles
* Editing software
* Chapters
* Camera settings

---

# Audio Metadata

* Artist
* Album
* Genre
* Track number
* Release year
* Cover image
* Lyrics
* Composer
* Duration
* Bitrate
* Sample rate
* Encoder used

---

# PDF Metadata

* Author
* Creator software
* Producer software
* Title
* Subject
* Keywords
* Page count
* Fonts used
* Embedded files
* JavaScript
* Creation date
* Modification date
* Security settings
* Hidden layers
* Digital signatures

---

# Office Files (DOCX, PPTX, XLSX)

These are extremely rich.

* Author
* Company
* Last editor
* Revision count
* Hidden sheets/slides
* Comments
* Track changes history
* Template used
* Creation timestamps
* Internal IDs
* Embedded objects
* Macros
* Hidden cells/text

---

# Executables / Apps

## EXE / ELF / Mach-O / APK / IPA

* Build timestamps
* Compiler used
* Libraries linked
* Architecture
* Certificates
* Signing keys
* Permissions
* Package names
* Internal resources
* Version numbers
* Debug symbols

---

# Archives (ZIP/RAR/TAR)

* Original filenames
* Directory structure
* Compression method
* Compression ratios
* Original timestamps
* Password flags

---

# Programming Files / Git

* Encoding
* Line endings
* Language detection
* Git history
* Commit author
* Commit timestamps
* Repository URLs

---

# Network / Download Metadata

Sometimes files keep:

* Download URL
* Browser used
* Referrer URL
* Email attachment info
* Cloud provider IDs
* Sync timestamps

---

# Hidden / Advanced Metadata

Some files contain:

* Steganographic data
* Embedded databases
* Binary blobs
* Hidden streams
* Alternate data streams
* Watermarks
* Tracking IDs
* UUIDs
* Internal object references

---

# How to Extract "Everything"

### Most powerful:

```bash
exiftool filename
```

### Binary strings:

```bash
strings filename
```

### Hex dump:

```bash
xxd filename
```

### Deep analysis:

```bash
binwalk filename
```

### PDFs:

```bash
pdfinfo file.pdf
```

### Videos:

```bash
ffprobe file.mp4
```

### Executables:

```bash
readelf binary
otool binary      # mac
objdump binary
```

### Archives:

```bash
zipinfo archive.zip
```

If you want **"show me literally every possible thing extractable from any file"**, the closest is:

```bash
exiftool file &&
strings file &&
xxd file &&
file file
```

That usually exposes far more than people expect.

