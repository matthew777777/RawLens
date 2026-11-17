# SPDX-License-Identifier: Apache-2.0
"""Summarise tools/shader-analysis/analyze.py output for the LK-R Lite shaders.

Mali (per core): work registers, thread occupancy, stack spill bytes, bound pipeline and total
cycles (longest path is undefined for loops of unknown length). Adreno A750: total instructions,
register footprint, scratch, ALU fiber occupancy, exposed long-latency share.
Usage: table.py SUMMARY.json [OTHER_SUMMARY.json]  (two files: before -> after)
"""
import json, os, sys


def parse(summary):
    d = json.load(open(summary))
    rows = {}
    for r in d['results']:
        cmd = r.get('command', [])
        name = os.path.basename(cmd[-1]).replace('.spv', '') if cmd else '?'
        try:
            j = json.loads(r.get('stdout', ''))
        except Exception:
            rows.setdefault(name, {})['err'] = 'no json'
            continue
        if 'malioc' in cmd[0]:
            core = cmd[cmd.index('--core') + 1].replace('Mali-', '')
            v = j['shaders'][0]['variants'][0]
            props = {p['name']: p['value'] for p in v.get('properties', [])}
            tc = v['performance']['total_cycles']
            rows.setdefault(name, {})[core] = dict(
                reg=props.get('work_registers_used'), occ=props.get('thread_occupancy'),
                spill=props.get('stack_spill_bytes'),
                bound='+'.join(str(b) for b in tc.get('bound_pipelines', []) if b),
                tot=max((c for c in tc['cycle_count'] if c is not None), default=0.0))
        else:
            st = {}
            for s in j['shader_info']:
                if s['name'] == 'main_stats':
                    st.update({x['name']: x['value'] for x in s['stats']})
                if s['name'] == 'performance_projection_stats':
                    for k in ('latency_hiding_stats', 'performance_stats'):
                        st.update({x['name']: x['value'] for x in s.get(k, [])})
            rows.setdefault(name, {})['A750'] = dict(
                ins=st.get('Total instruction count'), regs=st.get('Overall register footprint per shader instance'),
                scratch=st.get('Scratch memory usage per shader instance'), occ=st.get('ALU fiber occupancy percentage'),
                exposed=st.get('Cycles with exposed long latency sync instructions'))
    return rows


def fmt(r):
    def m(c):
        x = r.get(c)
        return f"{x['reg']:>3}r {x['occ']:>3}% sp{x['spill']:<3} {x['bound'][:10]:10s} {x['tot']:6.1f}cy" if x else '-'
    a = r.get('A750')
    aa = f"{a['ins']:>5}i {a['regs']:>3}r sc{a['scratch']:>3} occ{a['occ']:>4} expLL {a['exposed']}" if a else '-'
    return m('G615'), m('G720'), aa


def main():
    rows = parse(sys.argv[1])
    after = parse(sys.argv[2]) if len(sys.argv) > 2 else None
    print(f"{'shader':18s} | {'G615':38s} | {'G720':38s} | A750")
    for n in sorted(rows):
        print(f'{n:18s} | ' + ' | '.join(f'{c:38s}' for c in fmt(rows[n])))
        if after and n in after and after[n] != rows[n]:
            print(f"{'  -> after':18s} | " + ' | '.join(f'{c:38s}' for c in fmt(after[n])))


if __name__ == '__main__':
    main()
