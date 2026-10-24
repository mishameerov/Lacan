#!/usr/bin/env python3
"""Apply one bounded diagnostic hook to pinned MNN 3.6.1; no numerical changes."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]/'.tools/mnn/MNN-3.6.1'
assert subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()=='d407447ed56c4121a11ccbd266dc184ca1ead0c2'
p=root/'source/core/Pipeline.cpp'
s=p.read_text()
if 'lacan_mnn_record_execution' not in s:
    s=s.replace('namespace MNN {','extern "C" void lacan_mnn_record_execution(const char*,const char*,int);\nnamespace MNN {',1)
    marker='''            if (nullptr == cmd.info.get()) {
                auto code = cmd.execution->onExecute(cmd.workInputs, cmd.workOutputs);'''
    assert s.count(marker)==1
    s=s.replace(marker,'''            lacan_mnn_record_execution(cmd.info ? cmd.info->name().c_str() : "<internal>",
                cmd.info ? cmd.info->type().c_str() : "<internal>",
                cmd.execution && cmd.execution->backend() ? (int)cmd.execution->backend()->type() : -1);
'''+marker)
    p.write_text(s)
