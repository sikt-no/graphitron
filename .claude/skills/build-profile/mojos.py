import json, re, subprocess, sys, collections
rec, ev = sys.argv[1], 'com.github.marschall.maven.jfr.JfrEventListener$MojoEvent'
raw = subprocess.run(['jfr','print','--json','--events',ev,rec],
                     capture_output=True, text=True).stdout
def secs(iso):                      # PT1M23.4S -> 83.4
    m = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?', iso)
    h, mi, s = (m.group(1), m.group(2), m.group(3)) if m else (0, 0, 0)
    return int(h or 0)*3600 + int(mi or 0)*60 + float(s or 0)
tot, n = collections.Counter(), collections.Counter()
for e in json.loads(raw)['recording']['events']:
    v = e['values']
    tot["%s:%s (%s)" % (v['artifactId'], v['goal'], v['phase'])] += secs(v['duration'])
    n["%s:%s (%s)" % (v['artifactId'], v['goal'], v['phase'])] += 1
wall = sum(tot.values())
print("%-56s %8s %6s %6s" % ("mojo (phase)", "sec", "runs", "%"))
for k, v in tot.most_common(12):
    print("%-56s %8.1f %6d %5.1f%%" % (k, v, n[k], 100*v/wall))
print("%-56s %8.1f" % ("sum of mojos", wall))
