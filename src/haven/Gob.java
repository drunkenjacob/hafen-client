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

import java.util.*;
import java.util.function.*;
import haven.render.*;
import static haven.OCache.*;

public class Gob implements RenderTree.Node, Sprite.Owner, Skeleton.ModOwner, Skeleton.HasPose {
    public Coord2d rc;
    public double a;
    public boolean virtual = false;
    int clprio = 0;
    public long id;
    public final Glob glob;
    private boolean disposed = false;
    final Map<Class<? extends GAttrib>, GAttrib> attr = new HashMap<Class<? extends GAttrib>, GAttrib>();
    public final Collection<Overlay> ols = new ArrayList<Overlay>();
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private final Collection<SetupMod> setupmods = new ArrayList<>();
    private final Collection<ResAttr.Cell<?>> rdata = new LinkedList<ResAttr.Cell<?>>();
    private final Collection<ResAttr.Load> lrdata = new LinkedList<ResAttr.Load>();
    private final Object removalLock = new Object();
    private GeneralGobInfo gobInfo = null;
    private GobDamageInfo damage;
    private Hitbox hitbox;
    public static final ChangeCallback CHANGED = new ChangeCallback() {
	@Override
	public void added(Gob ob) {
	
	}
    
	@Override
	public void removed(Gob ob) {
	    ob.dispose();
	}
    };

    public static class Overlay implements RenderTree.Node {
	public final int id;
	public final Gob gob;
	public final Indir<Resource> res;
	public MessageBuf sdt;
	public Sprite spr;
	public boolean delign = false;
	private Collection<RenderTree.Slot> slots = null;
	private boolean added = false;

	public Overlay(Gob gob, int id, Indir<Resource> res, Message sdt) {
	    this.gob = gob;
	    this.id = id;
	    this.res = res;
	    this.sdt = new MessageBuf(sdt);
	    this.spr = null;
	}

	public Overlay(Gob gob, Sprite spr) {
	    this.gob = gob;
	    this.id = -1;
	    this.res = null;
	    this.sdt = null;
	    this.spr = spr;
	}

	private void init() {
	    if(spr == null) {
		spr = Sprite.create(gob, res.get(), sdt);
		if(added && (spr instanceof SetupMod))
		    gob.setupmods.add((SetupMod)spr);
	    }
	    if(slots == null)
		RUtils.multiadd(gob.slots, this);
	}

	private void add0() {
	    if(added)
		throw(new IllegalStateException());
	    if(spr instanceof SetupMod)
		gob.setupmods.add((SetupMod)spr);
	    added = true;
	}

	private void remove0() {
	    if(!added)
		throw(new IllegalStateException());
	    if(slots != null) {
		RUtils.multirem(new ArrayList<>(slots));
		slots = null;
	    }
	    if(spr instanceof SetupMod)
		gob.setupmods.remove(spr);
	    added = false;
	}

	public void remove() {
	    remove0();
	    gob.ols.remove(this);
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(spr);
	    if(slots == null)
		slots = new ArrayList<>(1);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    if(slots != null)
		slots.remove(slot);
	}
    }

    public static interface SetupMod {
	public default Pipe.Op gobstate() {return(null);}
	public default Pipe.Op placestate() {return(null);}
    }

    /* XXX: This whole thing didn't turn out quite as nice as I had
     * hoped, but hopefully it can at least serve as a source of
     * inspiration to redo attributes properly in the future. There
     * have already long been arguments for remaking GAttribs as
     * well. */
    public static class ResAttr {
	public boolean update(Message dat) {
	    return(false);
	}

	public void dispose() {
	}

	public static class Cell<T extends ResAttr> {
	    final Class<T> clsid;
	    Indir<Resource> resid = null;
	    MessageBuf odat;
	    public T attr = null;

	    public Cell(Class<T> clsid) {
		this.clsid = clsid;
	    }

	    public void set(ResAttr attr) {
		if(this.attr != null)
		    this.attr.dispose();
		this.attr = clsid.cast(attr);
	    }
	}

	private static class Load {
	    final Indir<Resource> resid;
	    final MessageBuf dat;

	    Load(Indir<Resource> resid, Message dat) {
		this.resid = resid;
		this.dat = new MessageBuf(dat);
	    }
	}

	@Resource.PublishedCode(name = "gattr", instancer = FactMaker.class)
	public static interface Factory {
	    public ResAttr mkattr(Gob gob, Message dat);
	}

	public static class FactMaker implements Resource.PublishedCode.Instancer {
	    public Factory make(Class<?> cl, Resource ires, Object... argv) {
		if(Factory.class.isAssignableFrom(cl))
		    return(Resource.PublishedCode.Instancer.stdmake(cl.asSubclass(Factory.class), ires, argv));
		if(ResAttr.class.isAssignableFrom(cl)) {
		    try {
			final java.lang.reflect.Constructor<? extends ResAttr> cons = cl.asSubclass(ResAttr.class).getConstructor(Gob.class, Message.class);
			return(new Factory() {
				public ResAttr mkattr(Gob gob, Message dat) {
				    return(Utils.construct(cons, gob, dat));
				}
			    });
		    } catch(NoSuchMethodException e) {
		    }
		}
		return(null);
	    }
	}
    }

    public Gob(Glob glob, Coord2d c, long id) {
	this.glob = glob;
	this.rc = c;
	this.id = id;
	if(id < 0)
	    virtual = true;
	if(GobDamageInfo.has(this)) {
	    damage = new GobDamageInfo(this);
	}
	setattr(new GeneralGobInfo(this));
    }

    public Gob(Glob glob, Coord2d c) {
	this(glob, c, -1);
    }
    
    private Map<Class<? extends GAttrib>, GAttrib> cloneattrs() {
	synchronized (this.attr) {
	    return new HashMap<>(this.attr);
	}
    }
    
    public void ctick(double dt) {
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values())
	    a.ctick(dt);
	loadrattr();
	for(Iterator<Overlay> i = ols.iterator(); i.hasNext();) {
	    Overlay ol = i.next();
	    if(ol.slots == null) {
		try {
		    ol.init();
		} catch(Loading e) {}
	    } else {
		boolean done = ol.spr.tick(dt);
		if((!ol.delign || (ol.spr instanceof Sprite.CDel)) && done) {
		    ol.remove0();
		    i.remove();
		}
	    }
	}
	updstate();
	if(virtual && ols.isEmpty() && (getattr(Drawable.class) == null))
	    glob.oc.remove(this);
    }

    public void gtick(Render g) {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    d.gtick(g);
	for(Overlay ol : ols) {
	    if(ol.spr != null)
		ol.spr.gtick(g);
	}
    }

    public void addol(Overlay ol, boolean async) {
	if(!async)
	    ol.init();
	ol.add0();
	ols.add(ol);
	overlayAdded(ol);
    }
    public void addol(Overlay ol) {
	addol(ol, true);
    }
    public void addol(Sprite ol) {
	addol(new Overlay(this, ol));
    }
    public void addol(Indir<Resource> res, Message sdt) {
	addol(new Overlay(this, -1, res, sdt));
    }

    public Overlay findol(int id) {
	for(Overlay ol : ols) {
	    if(ol.id == id)
		return(ol);
	}
	return(null);
    }

    private void overlayAdded(Overlay item) {
	try {
	    Indir<Resource> indir = item.res;
	    if(indir != null) {
		Resource res = indir.get();
		if(res != null) {
		    if(res.name.equals("gfx/fx/floatimg")) {
			processDmg(item.sdt.clone());
		    } else if(res.name.equals("gfx/fx/msrad")) {
			GobRadius.init();
		    }
//		    System.out.printf("overlayAdded: '%s'%n", res.name);
		}
	    }
	} catch (Loading ignored) {}
    }
    
    private void processDmg(MessageBuf msg) {
	try {
	    msg.rewind();
	    int v = msg.int32();
	    msg.uint8();
	    int c = msg.uint16();
	    System.out.println(String.format("processDmg v: %d, c: %d", v, c));
	    
	    if(damage == null) {
		damage = new GobDamageInfo(this);
	    }
	    damage.update(c, v);
	} catch (Exception ignored) {
	    ignored.printStackTrace();
	}
    }
    
    public void clearDmg() {
	if(damage != null) {
	    damage.dispose();
	    damage = null;
	}
    }
    
    public void rclick() {
	try {
	    MapView map = glob.sess.ui.gui.map;
	    map.wdgmsg("click", Coord.z, Coord.z, 3, 0, 0, (int) id, rc.floor(posres), 0, -1);
	} catch (Exception ignored) {}
    }
    
    public void tick() {
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for (GAttrib a : attr.values())
	    a.tick();
	loadrattr();
    }
    
    public void waitRemoval() throws InterruptedException {
	synchronized (removalLock) {
	    removalLock.wait(15000);
	    if(!disposed) {
		throw new InterruptedException();
	    }
	}
    }
    
    public boolean disposed() {return disposed;}
    
    public void dispose() {
	synchronized (removalLock) {
	    disposed = true;
	    removalLock.notifyAll();
	}
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values())
	    a.dispose();
	for(ResAttr.Cell rd : rdata) {
	    if(rd.attr != null)
		rd.attr.dispose();
    	}
	if(gobInfo != null){
	    gobInfo.dispose();
	    gobInfo = null;
	}
    }

    public void move(Coord2d c, double a) {
	Moving m = getattr(Moving.class);
	if(m != null)
	    m.move(c);
	this.rc = c;
	this.a = a;
    }

    public Coord3f getc() {
	Moving m = getattr(Moving.class);
	Coord3f ret = (m != null)?m.getc():getrc();
	DrawOffset df = getattr(DrawOffset.class);
	if(df != null)
	    ret = ret.add(df.off);
	return(ret);
    }

    public Coord3f getrc() {
	return(glob.map.getzp(rc));
    }

    protected Pipe.Op getmapstate(Coord3f pc) {
	Tiler tile = glob.map.tiler(glob.map.gettile(new Coord2d(pc).floor(MCache.tilesz)));
	return(tile.drawstate(glob, pc));
    }

    private Class<? extends GAttrib> attrclass(Class<? extends GAttrib> cl) {
	while(true) {
	    Class<?> p = cl.getSuperclass();
	    if(p == GAttrib.class)
		return(cl);
	    cl = p.asSubclass(GAttrib.class);
	}
    }

    public <C extends GAttrib> C getattr(Class<C> c) {
	synchronized (attr) {
	    GAttrib attr = this.attr.get(attrclass(c));
	    if(!c.isInstance(attr))
		return (null);
	    return (c.cast(attr));
	}
    }

    private void setattr(Class<? extends GAttrib> ac, GAttrib a) {
	synchronized (attr) {
	    GAttrib prev = attr.remove(ac);
	    if(prev != null) {
		if((prev instanceof RenderTree.Node) && (prev.slots != null))
		    RUtils.multirem(new ArrayList<>(prev.slots));
		if(prev instanceof SetupMod)
		    setupmods.remove(prev);
	    }
	    if(a != null) {
		if(a instanceof RenderTree.Node) {
		    try {
			RUtils.multiadd(this.slots, (RenderTree.Node) a);
		    } catch (Loading l) {
			if(prev instanceof RenderTree.Node) {
			    RUtils.multiadd(this.slots, (RenderTree.Node) prev);
			    attr.put(ac, prev);
			}
			if(prev instanceof SetupMod)
			    setupmods.add((SetupMod) prev);
			throw (l);
		    }
		}
		if(a instanceof SetupMod)
		    setupmods.add((SetupMod) a);
		attr.put(ac, a);
	    }
	    if(prev != null)
		prev.dispose();
	    if(ac == Drawable.class) {
		drawableUpdated();
	    }
	}
    }

    public void setattr(GAttrib a) {
	setattr(attrclass(a.getClass()), a);
    }

    public void delattr(Class<? extends GAttrib> c) {
	setattr(attrclass(c), null);
    }

    private Class<? extends ResAttr> rattrclass(Class<? extends ResAttr> cl) {
	while(true) {
	    Class<?> p = cl.getSuperclass();
	    if(p == ResAttr.class)
		return(cl);
	    cl = p.asSubclass(ResAttr.class);
	}
    }

    @SuppressWarnings("unchecked")
    public <T extends ResAttr> ResAttr.Cell<T> getrattr(Class<T> c) {
	for(ResAttr.Cell<?> rd : rdata) {
	    if(rd.clsid == c)
		return((ResAttr.Cell<T>)rd);
	}
	ResAttr.Cell<T> rd = new ResAttr.Cell<T>(c);
	rdata.add(rd);
	return(rd);
    }

    public static <T extends ResAttr> ResAttr.Cell<T> getrattr(Object obj, Class<T> c) {
	if(!(obj instanceof Gob))
	    return(new ResAttr.Cell<T>(c));
	return(((Gob)obj).getrattr(c));
    }

    private void loadrattr() {
	boolean upd = false;
	for(Iterator<ResAttr.Load> i = lrdata.iterator(); i.hasNext();) {
	    ResAttr.Load rd = i.next();
	    ResAttr attr;
	    try {
		attr = rd.resid.get().getcode(ResAttr.Factory.class, true).mkattr(this, rd.dat.clone());
	    } catch(Loading l) {
		continue;
	    }
	    ResAttr.Cell<?> rc = getrattr(rattrclass(attr.getClass()));
	    if(rc.resid == null)
		rc.resid = rd.resid;
	    else if(rc.resid != rd.resid)
		throw(new RuntimeException("Conflicting resattr resource IDs on " + rc.clsid + ": " + rc.resid + " -> " + rd.resid));
	    rc.odat = rd.dat;
	    rc.set(attr);
	    i.remove();
	    upd = true;
	}
    }

    public void setrattr(Indir<Resource> resid, Message dat) {
	for(Iterator<ResAttr.Cell<?>> i = rdata.iterator(); i.hasNext();) {
	    ResAttr.Cell<?> rd = i.next();
	    if(rd.resid == resid) {
		if(dat.equals(rd.odat))
		    return;
		if((rd.attr != null) && rd.attr.update(dat))
		    return;
		break;
	    }
	}
	for(Iterator<ResAttr.Load> i = lrdata.iterator(); i.hasNext();) {
	    ResAttr.Load rd = i.next();
	    if(rd.resid == resid) {
		i.remove();
		break;
	    }
	}
	lrdata.add(new ResAttr.Load(resid, dat));
	loadrattr();
    }

    public void delrattr(Indir<Resource> resid) {
	for(Iterator<ResAttr.Cell<?>> i = rdata.iterator(); i.hasNext();) {
	    ResAttr.Cell<?> rd = i.next();
	    if(rd.resid == resid) {
		i.remove();
		rd.attr.dispose();
		break;
	    }
	}
	for(Iterator<ResAttr.Load> i = lrdata.iterator(); i.hasNext();) {
	    ResAttr.Load rd = i.next();
	    if(rd.resid == resid) {
		i.remove();
		break;
	    }
	}
    }

    public void draw(GOut g) {}

    public static class GobClick extends Clickable {
	public final Gob gob;

	public GobClick(Gob gob) {
	    this.gob = gob;
	}

	public Object[] clickargs(ClickData cd) {
	    Object[] ret = {0, (int)gob.id, gob.rc.floor(OCache.posres), 0, -1};
	    for(Object node : cd.array()) {
		if(node instanceof Gob.Overlay) {
		    ret[0] = 1;
		    ret[3] = ((Gob.Overlay)node).id;
		}
		if(node instanceof FastMesh.ResourceMesh)
		    ret[4] = ((FastMesh.ResourceMesh)node).id;
	    }
	    return(ret);
	}

	public String toString() {
	    return(String.format("#<gob-click %d %s>", gob.id, gob.getres()));
	}
    }

    private class GobState implements Pipe.Op {
	final Pipe.Op mods;

	private GobState() {
	    if(setupmods.isEmpty()) {
		this.mods = null;
	    } else {
		Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
		int n = 0;
		for(SetupMod mod : setupmods) {
		    if((mods[n] = mod.gobstate()) != null)
			n++;
		}
		this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
	    }
	}

	public void apply(Pipe buf) {
	    if(!virtual)
		buf.prep(new GobClick(Gob.this));
	    buf.prep(new TickList.Monitor(Gob.this));
	    if(mods != null)
		buf.prep(mods);
	}

	public boolean equals(GobState that) {
	    return(Utils.eq(this.mods, that.mods));
	}
	public boolean equals(Object o) {
	    return((o instanceof GobState) && equals((GobState)o));
	}
    }
    private GobState curstate = null;
    private GobState curstate() {
	if(curstate == null)
	    curstate = new GobState();
	return(curstate);
    }

    private void updstate() {
	GobState nst;
	try {
	    nst = new GobState();
	} catch(Loading l) {
	    return;
	}
	if(!Utils.eq(nst, curstate)) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(nst);
	    this.curstate = nst;
	}
    }

    public void added(RenderTree.Slot slot) {
	slot.ostate(curstate());
	for(Overlay ol : ols) {
	    if(ol.slots != null)
		slot.add(ol);
	}
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values()) {
	    if(a instanceof RenderTree.Node)
		slot.add((RenderTree.Node)a);
	}
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }

    private Waitable.Queue updwait = null;
    private int updateseq = 0;
    void updated() {
	synchronized(this) {
	    updateseq++;
	    if(updwait != null)
		updwait.wnotify();
	}
    }

    public void updwait(Runnable callback, Consumer<Waitable.Waiting> reg) {
	/* Caller should probably synchronize on this already for a
	 * call like this to even be meaningful, but just in case. */
	synchronized(this) {
	    if(updwait == null)
		updwait = new Waitable.Queue();
	    reg.accept(updwait.add(callback));
	}
    }

    public static class DataLoading extends Loading {
	public final transient Gob gob;
	public final int updseq;

	/* It would be assumed that the caller has synchronized on gob
	 * while creating this exception. */
	public DataLoading(Gob gob, String message) {
	    super(message);
	    this.gob = gob;
	    this.updseq = gob.updateseq;
	}

	public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
	    synchronized(gob) {
		if(gob.updateseq != this.updseq) {
		    reg.accept(Waitable.Waiting.dummy);
		    callback.run();
		} else {
		    gob.updwait(callback, reg);
		}
	    }
	}
    }

    public Random mkrandoom() {
	return(Utils.mkrandoom(id));
    }

    public Resource getres() {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    return(d.getres());
	return(null);
    }

    public Skeleton.Pose getpose() {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    return(d.getpose());
	return(null);
    }

    public Indir<Resource> getires(){
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    return(d.getires());
	return(null);
    }
    
    private static final ClassResolver<Gob> ctxr = new ClassResolver<Gob>()
	.add(Glob.class, g -> g.glob)
	.add(Session.class, g -> g.glob.sess);
    public <T> T context(Class<T> cl) {return(ctxr.context(cl, this));}

    @Deprecated
    public Glob glob() {return(context(Glob.class));}

    /* Because generic functions are too nice a thing for Java. */
    public double getv() {
	Moving m = getattr(Moving.class);
	if(m == null)
	    return(0);
	return(m.getv());
    }

    public class Placed implements RenderTree.Node, TickList.Ticking, TickList.TickNode {
	private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
	private Placement cur;

	private Placed() {}

	private class Placement implements Pipe.Op {
	    final Pipe.Op flw, tilestate, mods;
	    final Coord3f oc, rc;
	    final double a;

	    Placement() {
		try {
		    Following flw = Gob.this.getattr(Following.class);
		    Pipe.Op flwxf = (flw == null) ? null : flw.xf();
		    Pipe.Op tilestate = null;
		    if(flwxf == null) {
			Coord3f oc = Gob.this.getc();
			Coord3f rc = new Coord3f(oc);
			rc.y = -rc.y;
			this.flw = null;
			this.oc = oc;
			this.rc = rc;
			this.a = Gob.this.a;
			tilestate = Gob.this.getmapstate(oc);
		    } else {
			this.flw = flwxf;
			this.oc = this.rc = null;
			this.a = Double.NaN;
		    }
		    this.tilestate = tilestate;
		    if(setupmods.isEmpty()) {
			this.mods = null;
		    } else {
			Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
			int n = 0;
			for(SetupMod mod : setupmods) {
			    if((mods[n] = mod.placestate()) != null)
				n++;
			}
			this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
		    }
		} catch(Loading bl) {
		    throw(new Loading(bl) {
			    public String getMessage() {return(bl.getMessage());}

			    public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
				Waitable.or(callback, reg, bl, Gob.this::updwait);
			    }
			});
		}
	    }

	    public boolean equals(Placement that) {
		if(this.flw != null) {
		    if(!Utils.eq(this.flw, that.flw))
			return(false);
		} else {
		    if(!(Utils.eq(this.oc, that.oc) && (this.a == that.a)))
			return(false);
		}
		if(!Utils.eq(this.tilestate, that.tilestate))
		    return(false);
		if(!Utils.eq(this.mods, that.mods))
		    return(false);
		return(true);
	    }

	    public boolean equals(Object o) {
		return((o instanceof Placement) && equals((Placement)o));
	    }

	    Pipe.Op gndst = null;
	    public void apply(Pipe buf) {
		if(this.flw != null) {
		    this.flw.apply(buf);
		} else {
		    if(gndst == null)
			gndst = Pipe.Op.compose(new Location(Transform.makexlate(new Matrix4f(), this.rc), "gobx"),
						new Location(Transform.makerot(new Matrix4f(), Coord3f.zu, (float)-this.a), "gob"));
		    gndst.apply(buf);
		}
		if(tilestate != null)
		    tilestate.apply(buf);
		if(mods != null)
		    mods.apply(buf);
	    }
	}

	public Pipe.Op placement() {
	    return(new Placement());
	}

	public void autotick(double dt) {
	    synchronized(Gob.this) {
		Placement np;
		try {
		    np = new Placement();
		} catch(Loading l) {
		    return;
		}
		if(!Utils.eq(this.cur, np))
		    update(np);
	    }
	}

	private void update(Placement np) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(np);
	    this.cur = np;
	}

	public void added(RenderTree.Slot slot) {
	    slot.ostate(curplace());
	    slot.add(Gob.this);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    slots.remove(slot);
	}

	public Pipe.Op curplace() {
	    if(cur == null)
		cur = new Placement();
	    return(cur);
	}

	public Coord3f getc() {
	    return((this.cur != null) ? this.cur.oc : null);
	}

	public TickList.Ticking ticker() {return(this);}
    }
    
    public void highlight() {
	GobHighlight h = getattr(GobHighlight.class);
	if(h == null) {
	    h = new GobHighlight(this);
	    setattr(h);
	}
	h.start();
    }
    
    public void drawableUpdated() {
	Boolean hitboxEnabled = CFG.DISPLAY_GOB_HITBOX.get();
	if(hitboxEnabled) {
	    if(hitbox != null) {
		hitbox.updateState();
	    } else if(!virtual || this instanceof MapView.Plob) {
		hitbox = Hitbox.forGob(this);
		if(hitbox != null) setattr(hitbox);
	    }
	} else if(hitbox != null) {
	    hitbox = null;
	    delattr(Hitbox.class);
	}
    }
    
    public final Placed placed = new Placed();
}
