#!/usr/bin/env python3
"""Public six-second GigaAM fixture: generate FP32 features and an ONNX CPU reference.

Use the isolated experiment environment; requires numpy, onnxruntime and
kaldi-native-fbank. Models/audio are local ignored test fixtures, never downloaded
by this script. Outputs go to .tools/giga-gpu-fixtures.
"""
from pathlib import Path
import wave, json, time, gc
import numpy as np
import onnxruntime as ort
import kaldi_native_fbank as knf
ort.disable_telemetry_events()
root=Path(__file__).resolve().parent.parent
out=root/'.tools/giga-gpu-fixtures';out.mkdir(exist_ok=True)
with wave.open(str(root/'.tools/test-audio/gigaam-example.wav')) as w:
 assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,16000)
 audio=np.frombuffer(w.readframes(6*16000),dtype='<i2').astype(np.float32)/32768
opts=knf.FbankOptions()
opts.frame_opts.samp_freq=16000
opts.frame_opts.frame_length_ms=25
opts.frame_opts.frame_shift_ms=10
opts.frame_opts.dither=0
opts.frame_opts.snip_edges=False
opts.frame_opts.remove_dc_offset=False
opts.frame_opts.preemph_coeff=0
opts.frame_opts.window_type='hann'
opts.frame_opts.round_to_power_of_two=False
opts.mel_opts.num_bins=64
opts.mel_opts.low_freq=0
opts.mel_opts.high_freq=8000
fbank=knf.OnlineFbank(opts)
fbank.accept_waveform(16000,audio.tolist()); fbank.input_finished()
f=np.stack([fbank.get_frame(i) for i in range(fbank.num_frames_ready)]).astype(np.float32)
so=ort.SessionOptions();so.intra_op_num_threads=2;so.inter_op_num_threads=1
s=ort.InferenceSession(str(root/'.tools/test-models/gigaam-v3-fp32/gigaam_v3_e2e_ctc.onnx'),sess_options=so,providers=['CPUExecutionProvider'])
meta=s.get_modelmeta().custom_metadata_map
print(meta,flush=True)
if meta.get('normalize_type')=='per_feature':
 f=(f-f.mean(0))/(np.sqrt(np.maximum((f*f).mean(0)-f.mean(0)**2,0))+1e-5)
x=f.T[None].copy();x.astype('<f4').tofile(out/'features.f32le')
(out/'frames.txt').write_text(str(len(f)));(out/'length.txt').write_text(str(len(f)))
t=time.perf_counter();y=s.run(None,{'features':x,'feature_lengths':np.array([len(f)],np.int64)})[0];elapsed=time.perf_counter()-t
y.astype('<f4').tofile(out/'logits-onnx.f32le')
tokens={}
for line in (root/'.tools/test-models/gigaam-v3-fp32/gigaam_v3_e2e_ctc_tokens.txt').read_text().splitlines():
 token,idx=line.rsplit(' ',1);tokens[int(idx)]=token
ids=y.argmax(-1).ravel();seq=[int(v) for i,v in enumerate(ids) if (i==0 or v!=ids[i-1]) and v!=256]
text=''.join(tokens[v] for v in seq).replace('▁',' ').strip()
report={'shape':list(y.shape),'seconds':elapsed,'finite':bool(np.isfinite(y).all()),'text':text,'metadata':meta,'frames':len(f)}
(out/'reference.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(report,flush=True)
