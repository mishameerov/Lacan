#!/usr/bin/env python3
"""Compare pulled CPU/GPU encoder files without importing numerical packages."""
import argparse
import array
import math
import sys
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('cpu', type=Path)
p.add_argument('gpu', type=Path)
a = p.parse_args()
def read(path):
    values = array.array('f')
    values.frombytes(path.read_bytes())
    if sys.byteorder != 'little':
        values.byteswap()
    return values
cpu, gpu = read(a.cpu), read(a.gpu)
if not cpu or len(cpu) != len(gpu) or not all(map(math.isfinite, cpu)) or not all(map(math.isfinite, gpu)):
    raise SystemExit('Invalid/non-finite/differently sized outputs')
delta = [abs(c - g) for c, g in zip(cpu, gpu)]
print(f'count={len(cpu)} max_abs={max(delta):.9g} rms_abs={math.sqrt(sum(x*x for x in delta)/len(delta)):.9g}')
print('Descriptive comparison only; no model-specific acceptance tolerance is established.')
