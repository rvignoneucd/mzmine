#!/usr/bin/env python3
"""Reads an Agilent ChemStation .D dataset with rainbow and streams it to mzmine.

mzmine calls this script as an external tool. It uses rainbow through its public API only
(``rainbow.read``) - none of rainbow's format logic is reproduced here, so this script is an
ordinary user of the library and stays independent of it.

rainbow is not distributed with mzmine. Install it with ``pip install rainbow-api``.
See README.txt in this directory.

Output is a compact binary stream on stdout, big-endian so the Java side can read it with
DataInputStream directly:

    magic       4 bytes  "MZCS"
    version     int32    currently 1
    scan count  int32
    per scan:
        retention time  float64  minutes
        point count     int32
        m/z             float64 * point count
        intensity       float64 * point count

Only non-zero points are written, so a scan carries its centroids rather than a dense row of
the full m/z axis.
"""

import argparse
import struct
import sys

MAGIC = b"MZCS"
VERSION = 1


def find_ms_file(directory):
    """Returns the MS data file in a parsed .D directory, or None when there is not one."""
    ms_files = [f for f in directory.datafiles if (f.detector or "").upper() == "MS"]
    if not ms_files:
        return None
    # A .D folder holds one MS payload; prefer the conventional name if several are present.
    for candidate in ms_files:
        if candidate.name.upper() == "DATA.MS":
            return candidate
    return ms_files[0]


def main():
    parser = argparse.ArgumentParser(description="Stream a ChemStation .D dataset to mzmine.")
    parser.add_argument("path", help="the .D directory")
    parser.add_argument("--precision", type=int, default=0,
                        help="decimals to round m/z to; 0 gives nominal mass (default)")
    args = parser.parse_args()

    try:
        import rainbow
    except ImportError:
        sys.stderr.write(
            "rainbow is not installed for this interpreter. Install it with:\n"
            "    pip install rainbow-api\n")
        return 3

    try:
        directory = rainbow.read(args.path, prec=args.precision)
    except Exception as error:  # rainbow raises bare Exception for unreadable input
        sys.stderr.write(f"rainbow could not read {args.path}: {error}\n")
        return 4

    data_file = find_ms_file(directory)
    if data_file is None:
        sys.stderr.write(f"No MS data found in {args.path}\n")
        return 5

    times = data_file.xlabels
    mzs = data_file.ylabels
    matrix = data_file.data

    out = sys.stdout.buffer
    out.write(MAGIC)
    out.write(struct.pack(">i", VERSION))
    out.write(struct.pack(">i", len(times)))

    for index in range(len(times)):
        row = matrix[index]
        # Keep only real signal; a dense row is mostly zeros for GC-MS.
        points = [(float(mz), float(value)) for mz, value in zip(mzs, row) if value > 0]
        out.write(struct.pack(">d", float(times[index])))
        out.write(struct.pack(">i", len(points)))
        if points:
            out.write(struct.pack(f">{len(points)}d", *[p[0] for p in points]))
            out.write(struct.pack(f">{len(points)}d", *[p[1] for p in points]))

    out.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
