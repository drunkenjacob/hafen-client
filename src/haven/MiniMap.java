/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.*;
import java.util.*;
import java.util.List;

import haven.MapFile.Segment;
import haven.MapFile.DataGrid;
import haven.MapFile.Grid;
import haven.MapFile.GridInfo;
import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static haven.OCache.posres;

public class MiniMap extends Widget {
    public static final Tex bg = Resource.loadtex("gfx/hud/mmap/ptex");
    public static final Tex nomap = Resource.loadtex("gfx/hud/mmap/nomap");
    public static final Tex plp = ((TexI)Resource.loadtex("gfx/hud/mmap/plp")).filter(haven.render.Texture.Filter.LINEAR);
    public final MapFile file;
    public Location curloc;
    public Location sessloc;
    public GobIcon.Settings iconconf;
    public List<DisplayIcon> icons = Collections.emptyList();
    protected Locator setloc;
    protected boolean follow;
    protected int zoomlevel = 0;
    protected DisplayGrid[] display;
    protected Area dgext, dtext;
    protected Segment dseg;
    protected int dlvl;
    protected Location dloc;

    public MiniMap(Coord sz, MapFile file) {
	super(sz);
	this.file = file;
    }

    public MiniMap(MapFile file) {
	this(Coord.z, file);
    }

    protected void attached() {
	if(iconconf == null) {
	    GameUI gui = getparent(GameUI.class);
	    if(gui != null)
		iconconf = gui.iconconf;
	}
	super.attached();
    }

    public static class Location {
	public final Segment seg;
	public final Coord tc;

	public Location(Segment seg, Coord tc) {
	    Objects.requireNonNull(seg);
	    Objects.requireNonNull(tc);
	    this.seg = seg; this.tc = tc;
	}
    }

    public interface Locator {
	Location locate(MapFile file) throws Loading;
    }

    public static class SessionLocator implements Locator {
	public final Session sess;
	private MCache.Grid lastgrid = null;
	private Location lastloc;

	public SessionLocator(Session sess) {this.sess = sess;}

	public Location locate(MapFile file) {
	    MCache map = sess.glob.map;
	    if(lastgrid != null) {
		synchronized(map.grids) {
		    if(map.grids.get(lastgrid.gc) == lastgrid)
			return(lastloc);
		}
		lastgrid = null;
		lastloc = null;
	    }
	    Collection<MCache.Grid> grids = new ArrayList<>();
	    synchronized(map.grids) {
		grids.addAll(map.grids.values());
	    }
	    for(MCache.Grid grid : grids) {
		GridInfo info = file.gridinfo.get(grid.id);
		if(info == null)
		    continue;
		Segment seg = file.segments.get(info.seg);
		if(seg != null) {
		    Location ret = new Location(seg, info.sc.sub(grid.gc).mul(cmaps));
		    lastgrid = grid;
		    lastloc = ret;
		    return(ret);
		}
	    }
	    throw(new Loading("No mapped grids found."));
	}
    }

    public static class MapLocator implements Locator {
	public final MapView mv;

	public MapLocator(MapView mv) {this.mv = mv;}

	public Location locate(MapFile file) {
	    Coord mc = new Coord2d(mv.getcc()).floor(MCache.tilesz);
	    if(mc == null)
		throw(new Loading("Waiting for initial location"));
	    MCache.Grid plg = mv.ui.sess.glob.map.getgrid(mc.div(cmaps));
	    GridInfo info = file.gridinfo.get(plg.id);
	    if(info == null)
		throw(new Loading("No grid info, probably coming soon"));
	    Segment seg = file.segments.get(info.seg);
	    if(seg == null)
		throw(new Loading("No segment info, probably coming soon"));
	    return(new Location(seg, info.sc.mul(cmaps).add(mc.sub(plg.ul))));
	}
    }

    public static class SpecLocator implements Locator {
	public final long seg;
	public final Coord tc;

	public SpecLocator(long seg, Coord tc) {this.seg = seg; this.tc = tc;}

	public Location locate(MapFile file) {
	    Segment seg = file.segments.get(this.seg);
	    if(seg == null)
		return(null);
	    return(new Location(seg, tc));
	}
    }

    public void center(Location loc) {
	curloc = loc;
    }

    public Location resolve(Locator loc) {
	if(!file.lock.readLock().tryLock())
	    throw(new Loading("Map file is busy"));
	try {
	    return(loc.locate(file));
	} finally {
	    file.lock.readLock().unlock();
	}
    }

    public Coord xlate(Location loc) {
	Location dloc = this.dloc;
	if((dloc == null) || (dloc.seg != loc.seg))
	    return(null);
	return(loc.tc.sub(dloc.tc).div(scalef()).add(sz.div(2)));
    }

    public Location xlate(Coord sc) {
	Location dloc = this.dloc;
	if(dloc == null)
	    return(null);
	Coord tc = sc.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
	return(new Location(dloc.seg, tc));
    }

    private Locator sesslocator;
    public void tick(double dt) {
	if(setloc != null) {
	    try {
		Location loc = resolve(setloc);
		center(loc);
		if(!follow)
		    setloc = null;
	    } catch(Loading l) {
	    }
	}
	if((sesslocator == null) && (ui != null) && (ui.sess != null))
	    sesslocator = new SessionLocator(ui.sess);
	if(sesslocator != null) {
	    try {
		sessloc = resolve(sesslocator);
	    } catch(Loading l) {
	    }
	}
	icons = findicons(icons);
    }

    public void center(Locator loc) {
	setloc = loc;
	follow = false;
    }

    public void follow(Locator loc) {
	setloc = loc;
	follow = true;
    }

    public class DisplayIcon {
	public final GobIcon icon;
	public final Gob gob;
	public final GobIcon.Image img;
	public Coord2d rc = null;
	public Coord sc = null;
	public double ang = 0.0;
	public Color col = Color.WHITE;
	public int z;
	public double stime;

	public DisplayIcon(GobIcon icon) {
	    this.icon = icon;
	    this.gob = icon.gob;
	    this.img = icon.img();
	    this.z = this.img.z;
	    this.stime = Utils.rtime();
	}

	public void update(Coord2d rc, double ang) {
	    this.rc = rc;
	    this.ang = ang;
	    if((this.rc == null) || (sessloc == null) || (dloc == null) || (dloc.seg != sessloc.seg))
		this.sc = null;
	    else
		this.sc = p2c(this.rc);
	}
    
	public Object tooltip() {
	    KinInfo kin = kin();
	    if(kin != null) {
		if(kin.isVillager() && kin.name.trim().isEmpty()) {
		    return "Villager";
		} else {
		    return kin.rendered();
		}
	    }
	    return icon.tooltip();
	}
    
	public KinInfo kin() {
	    return icon.gob.getattr(KinInfo.class);
	}
    
	public boolean isPlayer() {
	    return "gfx/hud/mmap/plo".equals(icon.res.get().name);
	}
    }

    public static class DisplayMarker {
	public static final Resource.Image flagbg, flagfg;
	public static final Coord flagcc;
	public final Marker m;
	public final Text tip;
	public Area hit;
	private Resource.Image img;
	private Coord imgsz;
	private Coord cc;

	static {
	    Resource flag = Resource.local().loadwait("gfx/hud/mmap/flag");
	    flagbg = flag.layer(Resource.imgc, 1);
	    flagfg = flag.layer(Resource.imgc, 0);
	    flagcc = UI.scale(flag.layer(Resource.negc).cc);
	}

	public DisplayMarker(Marker marker) {
	    this.m = marker;
	    this.tip = Text.render(m.nm);
	    if(marker instanceof PMarker)
		this.hit = Area.sized(flagcc.inv(), UI.scale(flagbg.sz));
	}

	public void draw(GOut g, Coord c) {
	    if(m instanceof PMarker) {
		Coord ul = c.sub(flagcc);
		g.chcolor(((PMarker)m).color);
		g.image(flagfg, ul);
		g.chcolor();
		g.image(flagbg, ul);
	    } else if(m instanceof SMarker) {
		SMarker sm = (SMarker)m;
		try {
		    if(cc == null) {
			Resource res = sm.res.loadsaved(Resource.remote());
			img = res.layer(Resource.imgc);
			imgsz = UI.scale(img.sz);
			Resource.Neg neg = res.layer(Resource.negc);
			cc = (neg != null)?neg.cc:imgsz.div(2);
			if(hit == null)
			    hit = Area.sized(cc.inv(), imgsz);
		    }
		} catch(Loading l) {
		} catch(Exception e) {
		    cc = Coord.z;
		}
		if(img != null)
		    g.image(img, c.sub(cc));
	    }
	}
    }

    public static class DisplayGrid {
	public final MapFile file;
	public final Segment seg;
	public final Coord sc;
	public final Area mapext;
	public final Indir<? extends DataGrid> gref;
	private DataGrid cgrid = null;
	private Tex img = null;
	private Defer.Future<Tex> nextimg = null;

	public DisplayGrid(Segment seg, Coord sc, int lvl, Indir<? extends DataGrid> gref) {
	    this.file = seg.file();
	    this.seg = seg;
	    this.sc = sc;
	    this.gref = gref;
	    mapext = Area.sized(sc.mul(cmaps.mul(1 << lvl)), cmaps.mul(1 << lvl));
	}

	public Tex img() {
	    DataGrid grid = gref.get();
	    if(grid != cgrid) {
		if(nextimg != null)
		    nextimg.cancel();
		if(grid instanceof MapFile.ZoomGrid) {
		    nextimg = Defer.later(() -> new TexI(grid.render(sc.mul(cmaps))));
		} else {
		    nextimg = Defer.later(new Defer.Callable<Tex>() {
			    MapFile.View view = new MapFile.View(seg);

			    public TexI call() {
				try(Locked lk = new Locked(file.lock.readLock())) {
				    for(int y = -1; y <= 1; y++) {
					for(int x = -1; x <= 1; x++) {
					    view.addgrid(sc.add(x, y));
					}
				    }
				    view.fin();
				    return(new TexI(MapSource.drawmap(view, Area.sized(sc.mul(cmaps), cmaps))));
				}
			    }
			});
		}
		cgrid = grid;
	    }
	    if(nextimg != null) {
		try {
		    img = nextimg.get();
		} catch(Loading l) {}
	    }
	    return(img);
	}

	private Collection<DisplayMarker> markers = Collections.emptyList();
	private int markerseq = -1;
	public Collection<DisplayMarker> markers(boolean remark) {
	    if(remark && (markerseq != file.markerseq)) {
		if(file.lock.readLock().tryLock()) {
		    try {
			ArrayList<DisplayMarker> marks = new ArrayList<>();
			for(Marker mark : file.markers) {
			    if((mark.seg == this.seg.id) && mapext.contains(mark.tc))
				marks.add(new DisplayMarker(mark));
			}
			marks.trimToSize();
			markers = (marks.size() == 0) ? Collections.emptyList() : marks;
			markerseq = file.markerseq;
		    } finally {
			file.lock.readLock().unlock();
		    }
		}
	    }
	    return(markers);
	}
    }

    private float scalef() {
	return(UI.unscale((float)(1 << dlvl)));
    }

    public Coord st2c(Coord tc) {
	return(UI.scale(tc.add(sessloc.tc).sub(dloc.tc).div(1 << dlvl)).add(sz.div(2)));
    }

    public Coord p2c(Coord2d pc) {
	return(st2c(pc.floor(tilesz)));
    }

    private void redisplay(Location loc) {
	Coord hsz = sz.div(2);
	Coord zmaps = cmaps.mul(1 << zoomlevel);
	Area next = Area.sized(loc.tc.sub(hsz.mul(UI.unscale((float)(1 << zoomlevel)))).div(zmaps),
	    UI.unscale(sz).div(cmaps).add(2, 2));
	if((display == null) || (loc.seg != dseg) || (zoomlevel != dlvl) || !next.equals(dgext)) {
	    DisplayGrid[] nd = new DisplayGrid[next.rsz()];
	    if((display != null) && (loc.seg == dseg) && (zoomlevel == dlvl)) {
		for(Coord c : dgext) {
		    if(next.contains(c))
			nd[next.ri(c)] = display[dgext.ri(c)];
		}
	    }
	    display = nd;
	    dseg = loc.seg;
	    dlvl = zoomlevel;
	    dgext = next;
	    dtext = Area.sized(next.ul.mul(zmaps), next.sz().mul(zmaps));
	}
	dloc = loc;
	if(file.lock.readLock().tryLock()) {
	    try {
		for(Coord c : dgext) {
		    if(display[dgext.ri(c)] == null)
			display[dgext.ri(c)] = new DisplayGrid(dloc.seg, c, dlvl, dloc.seg.grid(dlvl, c.mul(1 << dlvl)));
		}
	    } finally {
		file.lock.readLock().unlock();
	    }
	}
    }

    public void drawmap(GOut g) {
	Coord hsz = sz.div(2);
	for(Coord c : dgext) {
	    Tex img;
	    try {
		DisplayGrid disp = display[dgext.ri(c)];
		if((disp == null) || ((img = disp.img()) == null))
		    continue;
	    } catch(Loading l) {
		continue;
	    }
	    Coord ul = UI.scale(c.mul(cmaps)).sub(dloc.tc.div(scalef())).add(hsz);
	    g.image(img, ul, UI.scale(img.sz()));
	}
    }

    public void drawmarkers(GOut g) {
	Coord hsz = sz.div(2);
	for(Coord c : dgext) {
	    DisplayGrid dgrid = display[dgext.ri(c)];
	    if(dgrid == null)
		continue;
	    for(DisplayMarker mark : dgrid.markers(true))
		mark.draw(g, mark.m.tc.sub(dloc.tc).div(scalef()).add(hsz));
	}
    }

    public List<DisplayIcon> findicons(Collection<? extends DisplayIcon> prev) {
	if((ui.sess == null) || (iconconf == null))
	    return(Collections.emptyList());
	Map<Gob, DisplayIcon> pmap = Collections.emptyMap();
	if(prev != null) {
	    pmap = new HashMap<>();
	    for(DisplayIcon disp : prev)
		pmap.put(disp.gob, disp);
	}
	List<DisplayIcon> ret = new ArrayList<>();
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob gob : oc) {
		try {
		    GobIcon icon = gob.getattr(GobIcon.class);
		    if(icon != null) {
			GobIcon.Setting conf = iconconf.get(icon.res.get());
			if((conf != null) && conf.show && GobIconSettings.GobCategory.categorize(conf).enabled()) {
			    DisplayIcon disp = pmap.get(gob);
			    if(disp == null)
				disp = new DisplayIcon(icon);
			    disp.update(gob.rc, gob.a);
			    KinInfo kin = gob.getattr(KinInfo.class);
			    if((kin != null) && (kin.group < BuddyWnd.gc.length))
				disp.col = BuddyWnd.gc[kin.group];
			    ret.add(disp);
			}
		    }
		} catch(Loading l) {}
	    }
	}
	Collections.sort(ret, (a, b) -> a.z - b.z);
	if(ret.size() == 0)
	    return(Collections.emptyList());
	return(ret);
    }

    public void drawicons(GOut g) {
	if((sessloc == null) || (dloc.seg != sessloc.seg))
	    return;
	for(DisplayIcon disp : icons) {
	    if(disp.sc == null)
		continue;
	    GobIcon.Image img = disp.img;
	    if(disp.isPlayer()) {
		g.chcolor(disp.kin() != null ? Color.WHITE : Color.RED);
		g.aimage(RadarCFG.Symbols.$circle.tex, disp.sc, 0.5, 0.5);
	    }
	    
	    if(disp.col != null)
		g.chcolor(disp.col);
	    else
		g.chcolor();
	    if(!img.rot)
		g.image(img.tex, disp.sc.sub(img.cc));
	    else
		g.rotimage(img.tex, disp.sc, img.cc, -disp.ang + img.ao);
	}
	g.chcolor();
    }

    public void remparty() {
	Set<Gob> memb = new HashSet<>();
	synchronized(ui.sess.glob.party.memb) {
	    for(Party.Member m : ui.sess.glob.party.memb.values()) {
		Gob gob = m.getgob();
		if(gob != null)
		    memb.add(gob);
	    }
	}
	for(Iterator<DisplayIcon> it = icons.iterator(); it.hasNext();) {
	    DisplayIcon icon = it.next();
	    if(memb.contains(icon.gob))
		it.remove();
	}
    }

    public void drawparty(GOut g) {
	synchronized(ui.sess.glob.party.memb) {
	    for(Party.Member m : ui.sess.glob.party.memb.values()) {
		try {
		    Coord2d ppc = m.getc();
		    if(ppc == null)
			continue;
		    g.chcolor(Color.WHITE);
		    g.aimage(RadarCFG.Symbols.$circle.tex, p2c(ppc), 0.5, 0.5);
		    g.chcolor(m.col.getRed(), m.col.getGreen(), m.col.getBlue(), 255);
		    g.rotimage(plp, p2c(ppc), plp.sz().div(2), -m.geta() - (Math.PI / 2));
		    g.chcolor();
		} catch(Loading l) {}
	    }
	}
    }

    public void drawparts(GOut g){
	drawmap(g);
	drawmarkers(g);
	if(CFG.MMAP_GRID.get()) {drawgrid(g);}
	if(CFG.MMAP_VIEW.get()) {drawview(g);}
	if(dlvl <= 1)
	    drawicons(g);
	drawparty(g);
    }

    public void draw(GOut g) {
	Location loc = this.curloc;
	if(loc == null)
	    return;
	redisplay(loc);
	remparty();
	drawparts(g);
    }

    private static boolean hascomplete(DisplayGrid[] disp, Area dext, Coord c) {
	DisplayGrid dg = disp[dext.ri(c)];
	if(dg == null)
	    return(false);
	return(dg.gref.get() != null);
    }

    protected boolean allowzoomout() {
	DisplayGrid[] disp = this.display;
	Area dext = this.dgext;
	try {
	    for(int x = dext.ul.x; x < dext.br.x; x++) {
		if(hascomplete(disp, dext, new Coord(x, dext.ul.y)) ||
		   hascomplete(disp, dext, new Coord(x, dext.br.y - 1)))
		    return(true);
	    }
	    for(int y = dext.ul.y; y < dext.br.y; y++) {
		if(hascomplete(disp, dext, new Coord(dext.ul.x, y)) ||
		   hascomplete(disp, dext, new Coord(dext.br.x - 1, y)))
		    return(true);
	    }
	} catch(Loading l) {
	    return(false);
	}
	return(false);
    }

    public DisplayIcon iconat(Coord c) {
	for(ListIterator<DisplayIcon> it = icons.listIterator(icons.size()); it.hasPrevious();) {
	    DisplayIcon disp = it.previous();
	    GobIcon.Image img = disp.img;
	    if((disp.sc != null) && c.isect(disp.sc.sub(img.cc), img.tex.sz()))
		return(disp);
	}
	return(null);
    }

    public DisplayMarker markerat(Coord tc) {
	for(DisplayGrid dgrid : display) {
	    if(dgrid == null)
		continue;
	    for(DisplayMarker mark : dgrid.markers(false)) {
		if((mark.hit != null) && mark.hit.contains(tc.sub(mark.m.tc).div(scalef())))
		    return(mark);
	    }
	}
	return(null);
    }

    public boolean clickloc(Location loc, int button, boolean press) {
	return(false);
    }

    public boolean clickicon(DisplayIcon icon, Location loc, int button, boolean press) {
	return(false);
    }

    public boolean clickmarker(DisplayMarker mark, Location loc, int button, boolean press) {
	return(false);
    }

    private UI.Grab drag;
    private boolean dragging;
    private Coord dsc, dmc;
    public boolean dragp(int button) {
	return(button == 1);
    }

    private Location dsloc;
    private DisplayIcon dsicon;
    private DisplayMarker dsmark;
    public boolean mousedown(Coord c, int button) {
	dsloc = xlate(c);
	if(dsloc != null) {
	    dsicon = iconat(c);
	    dsmark = markerat(dsloc.tc);
	    if((dsicon != null) && clickicon(dsicon, dsloc, button, true))
		return(true);
	    if((dsmark != null) && clickmarker(dsmark, dsloc, button, true))
		return(true);
	    if(clickloc(dsloc, button, true))
		return(true);
	} else {
	    dsloc = null;
	    dsicon = null;
	    dsmark = null;
	}
	if(dragp(button)) {
	    Location loc = curloc;
	    if((drag == null) && (loc != null)) {
		drag = ui.grabmouse(this);
		dsc = c;
		dmc = loc.tc;
		dragging = false;
	    }
	    return(true);
	}
	return(super.mousedown(c, button));
    }

    public void mousemove(Coord c) {
	if(drag != null) {
	    if(dragging) {
		setloc = null;
		follow = false;
		curloc = new Location(curloc.seg, dmc.add(dsc.sub(c).mul(scalef())));
	    } else if(c.dist(dsc) > 5) {
		dragging = true;
	    }
	}
	super.mousemove(c);
    }

    public boolean mouseup(Coord c, int button) {
	if((drag != null) && (button == 1)) {
	    drag.remove();
	    drag = null;
	}
	release: if(!dragging && (dsloc != null)) {
	    if((dsicon != null) && clickicon(dsicon, dsloc, button, false))
		break release;
	    if((dsmark != null) && clickmarker(dsmark, dsloc, button, false))
		break release;
	    if(clickloc(dsloc, button, false))
		break release;
	}
	dsloc = null;
	dsicon = null;
	dsmark = null;
	dragging = false;
	return(super.mouseup(c, button));
    }

    public boolean mousewheel(Coord c, int amount) {
	if(amount > 0) {
	    if(allowzoomout())
		zoomlevel = Math.min(zoomlevel + 1, dlvl + 1);
	} else {
	    zoomlevel = Math.max(zoomlevel - 1, 0);
	}
	return(true);
    }

    public Object tooltip(Coord c, Widget prev) {
	if(dloc != null) {
	    Coord tc = c.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
	    DisplayMarker mark = markerat(tc);
	    if(mark != null) {
		return(mark.tip);
	    }
	    
	    DisplayIcon icon = iconat(c);
	    if(icon != null) {
		return icon.tooltip();
	    }
	}
	return(super.tooltip(c, prev));
    }

    public void mvclick(MapView mv, Coord mc, Location loc, Gob gob, int button) {
	if(mc == null) mc = ui.mc;
	if((sessloc != null) && (sessloc.seg == loc.seg)) {
	    if(gob == null)
		mv.wdgmsg("click", mc,
			  loc.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2)).floor(posres),
			  button, ui.modflags());
	    else
		mv.wdgmsg("click", mc,
			  loc.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2)).floor(posres),
			  button, ui.modflags(), 0,
			  (int)gob.id,
			  gob.rc.floor(posres),
			  0, -1);
	}
    }
    
    void drawgrid(GOut g) {
	int zmult = 1 << zoomlevel;
	Coord offset = sz.div(2).sub(dloc.tc.div(scalef()));
	Coord zmaps = cmaps.div( (float)zmult);
    
	double width = UI.scale(1f);
	Color col = g.getcolor();
	g.chcolor(Color.RED);
	for (int x = dgext.ul.x * zmult; x < dgext.br.x * zmult; x++) {
	    Coord a = UI.scale(zmaps.mul(x, dgext.ul.y * zmult)).add(offset);
	    Coord b = UI.scale(zmaps.mul(x, dgext.br.y * zmult)).add(offset);
	    if(a.x >= 0 && a.x <= sz.x) {
		a.y = Utils.clip(a.y, 0, sz.y);
		b.y = Utils.clip(b.y, 0, sz.y);
		g.line(a, b, width);
	    }
	}
	for (int y = dgext.ul.y * zmult; y < dgext.br.y * zmult; y++) {
	    Coord a = UI.scale(zmaps.mul(dgext.ul.x * zmult, y)).add(offset);
	    Coord b = UI.scale(zmaps.mul(dgext.br.x * zmult, y)).add(offset);
	    if(a.y >= 0 && a.y <= sz.y) {
		a.x = Utils.clip(a.x, 0, sz.x);
		b.x = Utils.clip(b.x, 0, sz.x);
		g.line(a, b, width);
	    }
	}
	g.chcolor(col);
    }
    
    public static final Coord VIEW_SZ = UI.scale(MCache.sgridsz.mul(9).div(tilesz.floor()));// view radius is 9x9 "server" grids
    public static final Color VIEW_BG_COLOR = new Color(255, 255, 255, 60);
    public static final Color VIEW_BORDER_COLOR = new Color(0, 0, 0, 128);
    
    void drawview(GOut g) {
	int zmult = 1 << zoomlevel;
	Coord2d sgridsz = new Coord2d(MCache.sgridsz);
	Gob player = ui.gui.map.player();
	if(player != null) {
	    Coord rc = p2c(player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz));
	    g.chcolor(VIEW_BG_COLOR);
	    g.frect(rc, VIEW_SZ.div(zmult));
	    g.chcolor(VIEW_BORDER_COLOR);
	    g.rect(rc, VIEW_SZ.div(zmult));
	    g.chcolor();
	}
    }
}
