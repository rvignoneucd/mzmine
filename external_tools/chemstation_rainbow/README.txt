Agilent ChemStation reader (rainbow)
====================================

mzmine reads legacy Agilent ChemStation .D/DATA.MS datasets by calling the rainbow library
through the read_chemstation.py script in this directory.

rainbow is NOT distributed with mzmine. It is licensed under LGPL-3.0, and mzmine uses it as a
separate tool rather than incorporating its code. You install it yourself:

    https://github.com/evanyeyeye/rainbow

Setup
-----
1. Install Python 3.9 or newer.
2. Install rainbow:

       pip install rainbow-api

3. In mzmine, set the ChemStation reader interpreter path if Python is not on PATH.

To keep it self-contained, create a virtual environment inside this directory instead:

    python -m venv .venv
    .venv\Scripts\pip install rainbow-api        (Windows)
    .venv/bin/pip install rainbow-api            (macOS / Linux)

mzmine looks for that .venv automatically, so no configuration is needed if you use it.

Only read_chemstation.py belongs to mzmine. Everything pip installs here, rainbow included,
remains under its own licence and is excluded from version control.
