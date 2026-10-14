"""Guest process CPU accounting; not translated-code/FEX overhead attribution."""
import re


def parse_stat(text):
    match=re.fullmatch(r'(\d+) \((.*)\) (\S) (.*)',text.strip())
    if not match:raise ValueError('Invalid process stat')
    fields=[match[3]]+match[4].split()
    if len(fields)<22:raise ValueError('Truncated process stat')
    return {'pid':match[1],'start_ticks':int(fields[19]),
            'cpu_ticks':int(fields[11])+int(fields[12]),
            'minor_faults':int(fields[7]),'major_faults':int(fields[9])}


def summarize_cpu(events):
    counters=[e for e in events if e.get('event')=='guest_cpu_counter']
    intervals=[];rejected=[]
    for a,b in zip(counters,counters[1:]):
        try:
            left,right=parse_stat(a['stat']),parse_stat(b['stat'])
            hz=int(a['clock_ticks'])
            if hz<=0 or int(b['clock_ticks'])!=hz:raise ValueError('Clock tick rate changed or invalid')
            if (left['pid'],left['start_ticks'])!=(right['pid'],right['start_ticks']):
                raise ValueError('PID changed or reused')
            if str(a['pid'])!=left['pid'] or str(b['pid'])!=right['pid']:
                raise ValueError('Counter PID does not match event')
            elapsed=(b['host_monotonic_ns']-a['host_monotonic_ns'])/1e9
            ticks=right['cpu_ticks']-left['cpu_ticks']
            minor=right['minor_faults']-left['minor_faults'];major=right['major_faults']-left['major_faults']
            if elapsed<=0 or min(ticks,minor,major)<0:raise ValueError('Non-monotonic CPU or fault counters')
            cpu_seconds=ticks/hz
            intervals.append({'elapsed_s':elapsed,'cpu_s':cpu_seconds,
                'one_core_percent':100*cpu_seconds/elapsed,'minor_faults':minor,'major_faults':major})
        except (ValueError,KeyError,TypeError) as exc:rejected.append(str(exc))
    elapsed=sum(v['elapsed_s'] for v in intervals);cpu=sum(v['cpu_s'] for v in intervals)
    return {'available':bool(intervals),'intervals':intervals,'rejected':rejected,
        'one_core_percent':100*cpu/elapsed if elapsed else None,
        'definition':'100% equals one CPU core; multi-threaded process can exceed 100%',
        'scope':'Guest process user+system CPU, including runtime work; not isolated FEX overhead',
        'clock_scope':'Host-observed intervals include ADB sampling latency; not exact device-time CPU profiling'}