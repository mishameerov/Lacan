#!/usr/bin/env python3
"""Compare public-fixture FP32 GigaAM logits; reports evidence, not a universal tolerance."""
import argparse
import json
from pathlib import Path
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('candidate', type=Path)
parser.add_argument('--reference-dir', type=Path, default=Path('.tools/giga-gpu-fixtures'))
parser.add_argument('--tokens', type=Path, default=Path('.tools/test-models/gigaam-v3-fp32/gigaam_v3_e2e_ctc_tokens.txt'))
args = parser.parse_args()
metadata = json.loads((args.reference_dir / 'reference.json').read_text())
shape = metadata['shape']
reference = np.fromfile(args.reference_dir / 'logits-onnx.f32le', dtype='<f4').reshape(shape)
candidate = np.fromfile(args.candidate, dtype='<f4').reshape(shape)
if not np.isfinite(candidate).all():
    raise ValueError('Candidate logits contain nonfinite values')
tokens = {}
for line in args.tokens.read_text().splitlines():
    text, index = line.rsplit(' ', 1)
    tokens[int(index)] = text

def decode(logits):
    ids = logits.argmax(-1).ravel()
    return ''.join(tokens[int(value)] for i, value in enumerate(ids)
                   if value != 256 and (i == 0 or value != ids[i-1])).replace('▁', ' ').strip()

print(json.dumps({
    'shape': shape,
    'max_abs_error': float(np.max(np.abs(reference-candidate))),
    'rms_error': float(np.sqrt(np.mean((reference-candidate)**2))),
    'argmax_agreement': float(np.mean(reference.argmax(-1) == candidate.argmax(-1))),
    'reference_text': decode(reference),
    'candidate_text': decode(candidate),
    'ctc_text_equal': decode(reference) == decode(candidate),
}, ensure_ascii=False, indent=2))
