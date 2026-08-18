# Third-party attribution

Code in this build that originates elsewhere, and the terms it is used under.

## rainbow — Agilent ChemStation format decoding

**Used in:** `ChemStationMsParser`, and the header constants in
`RawDataFileTypeDetector.isChemStationMsFile`

**Source project:** [rainbow](https://github.com/evanyeyeye/rainbow) by Evan Shi and Eugene Kwan,
specifically the `.ms` parsing logic in `rainbow/agilent/chemstation.py`

**Source licence:** LGPL-3.0

**How it is used:** the decoding logic was ported to Java. That makes it a derivative work, and
LGPL-3.0 is not compatible with mzmine's MIT licence, so it could not be redistributed here on the
strength of the licence alone.

**Permission:** granted publicly by the project's maintainer at
[evanyeyeye/rainbow#72](https://github.com/evanyeyeye/rainbow/issues/72):

> Thanks for reaching out! You want to transpile to Java? Please go ahead! I'm so glad this was
> useful to you!
>
> — Eugene Kwan, 18 August 2026

**What this means in practice:**

- Keep the attribution notice in `ChemStationMsParser`. It is a condition of the permission, not a
  courtesy.
- If that code is ever proposed to upstream mzmine, link this record and the issue above so
  maintainers can see the provenance is settled.
- Permission covers the ported decoding logic. It is not a general licence to copy other parts of
  rainbow; anything further needs its own conversation.

**Related:** an earlier revision of this build read ChemStation data by calling rainbow as an
external tool instead of porting it, which needed no permission. That approach was removed once
permission was granted, because it required every user to install Python and rainbow. It remains
recoverable from git history if the native path is ever a problem.

## NIST MSPepSearch

**Used in:** `NistMsSearchTask`, via an external process

**Not redistributed.** MSPepSearch is a free NIST download and is not part of a standard NIST MS
Search installation. mzmine runs it as a separate tool; users supply their own copy, along with
their own licensed NIST libraries. Nothing from NIST is committed to this repository.

See `docs/nist-mspepsearch/README.md` for setup.

## INFICON HAPSITE

**Used in:** `HapsiteHpsParser`

Written for this project from the container layout. No third-party code or terms apply.
