import re, os, glob, sys

ROOT="/media/elm/208bb34c-5445-4054-a4f9-983f8bd4bf6d/Documents/lowhaos/lowha/apps"
problems=[]

for mod in ("LowhaCoreService","LowhaSettings"):
    base=os.path.join(ROOT,mod)
    if not os.path.isdir(base): continue
    code="\n".join(open(p).read() for p in glob.glob(base+"/src/**/*.kt",recursive=True))
    resfiles=glob.glob(base+"/res/**/*.xml",recursive=True)
    restext="\n".join(open(p).read() for p in resfiles)
    layoutnames={os.path.basename(p)[:-4] for p in glob.glob(base+"/res/layout/*.xml")}
    drawnames={os.path.basename(p).rsplit('.',1)[0] for p in glob.glob(base+"/res/drawable/*")}
    strings=set(re.findall(r'<string name="([^"]+)"', restext))
    styles=set(re.findall(r'<style name="([^"]+)"', restext))
    ids=set(re.findall(r'@\+id/([A-Za-z0-9_]+)', restext))

    # also allow manifest refs
    man=open(base+"/AndroidManifest.xml").read()

    refs=re.findall(r'(?<!android\.)\bR\.(layout|drawable|string|style|id)\.([A-Za-z0-9_]+)', code)
    for kind,name in set(refs):
        ok = {"layout":name in layoutnames,
              "drawable":name in drawnames,
              "string":name in strings,
              "style":name in styles,
              "id":name in ids}[kind]
        if not ok:
            problems.append(f"{mod}: R.{kind}.{name} -> NOT DEFINED")

    # manifest resource refs
    for kind,name in set(re.findall(r'@(string|drawable|style|layout)/([A-Za-z0-9_.]+)', man)):
        pool={"string":strings,"drawable":drawnames,"style":styles,"layout":layoutnames}[kind]
        if name not in pool:
            problems.append(f"{mod}/manifest: @{kind}/{name} -> NOT DEFINED")

    # string format args sanity: %1$d vs %1$s used with getString(int,int)
    print(f"{mod}: {len(set(refs))} code refs, {len(strings)} strings, {len(layoutnames)} layouts, {len(drawnames)} drawables")

print()
if problems:
    print("PROBLEMS:")
    for p in problems: print("  "+p)
    sys.exit(1)
print("all resource references resolve")
