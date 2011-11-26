/*************************************************************************
    PatternPlotter - Jonatan Liljedahl <lijon@kymatica.com>

    plot patterns in userview

usage:

    p = PatternPlotter(pattern, plotSpecs);

    where pattern is an event pattern and plotSpecs an array of Event's describing what and how to plot.

    p.bounds    returns the bounds of the plot in pixels, as a Rect with 0@0 as origin
    p.draw      draw the plots. call this inside your userView

    p.gui       create a window with a view that draws the plots

properties:

    p.length    duration to plot (seconds)
    p.xscale    time zoom level (pixels per beat)
    p.xmargin   horizontal margin (pixels)
    p.tickColor color of vertical event tick lines
    p.tickDash  dash of vertical event tick lines
    p.bounds
    p.tickFullHeight
                if false, draw event tick line only from top data point to bottom data point
    p.pattern   the pattern
    p.plotSpecs the plotSpecs
    p.defaults  default plotSpec

plotSpec keys:

    a plotSpec is an Event in the form (param1: value, param2: value, ...)

  static parameters:

    type        \linear, \steps, \levels, \dots
    height      the height of this plot in pixels
    lenKey      the pattern event key to use for line length in \levels type
    label       custom label, or nil to use the pattern key of y param
    labelColor  color of label

  dynamic parameters:

    y           vertical position of data point (in the range 0.0 - 1.0)
    lineWidth   line width (pixels)
    padding     top and bottom padding (pixels)
    dotSize     size of data point circle (pixels)
    dotColor    color of data point circle (Color)
    dash        line dash (FloatArray)
    color       line color (Color)
    
  the dynamic parameters can take a single value:

    value       (like 1.0 or Color.black or anything that responds to .value, like Function or Stream)

  or an Association between a pattern event key and mapping spec/function:

    \keyName -> {|input_value| do_something_and_return_output_value }
    \keyName -> anything that responds to .asSpec

    A spec is .unmap'd to the range 0.0 - 1.0

example:

    PatternPlotter(
        Pbind(
            \degree, Pseq([0,3,6,[3,5,6],[4,1],2],inf),
            \amp, Pseq([0.2,0.6,0.4,1],inf),
            \foo, Pwhite(0,10,inf),
            \dur, Pseq([0.5,0.25,1],inf)
        ),
        [
            (y: \freq -> [200,700,\exp], dotSize: \amp -> _.linlin(0,1,1,8), dotColor: Color(0,0,0,0.4), \lineWidth:3),
            (y: \foo -> [0,10], dotSize: 3, type: \linear, height: 100)
        ]
    ).length_(12).tickFullHeight_(false).gui;

********************************************************************/

PatternPlotter {
    var <length, // duration to plot (in seconds)
        <>xscale = 50, // time to pixels factor (time zoom level)
        <>xmargin = 10;

    var <>tickColor,
        <>tickDash,
        <>tickFullHeight = true;

    var <>pattern, <>defaults, <plotSpecs;

    var <bounds;

    *new {|pattern,plotSpecs|
        ^super.new.init(pattern, plotSpecs);
    }

    gui {
        var win;
        UserView(win = Window("Pattern Plot",bounds).front,bounds).background_(Color.white).drawFunc_({|v|
            this.draw;
        }).refresh;
        ^win;
    }

    init {|aPattern, aPlotSpecs|
        defaults = (
            y: \freq -> ControlSpec(20,20000,\exp),
            height: 200,
            type: \levels,
            lenKey: \sustain,
            label: nil,
            lineWidth: 1,
            padding: 20,
            dotSize: 2,
            dotColor: Color.black,
            labelColor: Color(0.3,0.6,0.4),
            dash: FloatArray[1,0],
            color: Color.black
        );
        bounds = Rect(0,0,0,0);
        tickColor = Color.black.alpha_(0.5);
        tickDash = FloatArray[1,2];
        this.length = 16;
        this.pattern = aPattern;
        this.plotSpecs = aPlotSpecs;
    }

    parmap {|e,v|
        ^if(v.class==Association) {
            if(v.value.isKindOf(AbstractFunction)) {
                v.value.value(e[v.key]).value
            } {
                v.value.asSpec.unmap(e[v.key].value)
            }
        } {
            v.value; // ? 0
        }
    }

    length_ {|len|
        length = len;
        bounds.width = length*xscale+(xmargin*2);
    }

    plotSpecs_ {|aPlotSpecs|
        var height = 0;
        plotSpecs = aPlotSpecs.reverse;
        plotSpecs.do {|p|
            p.parent = defaults;
            height = height + (p.padding*2) + p.height;
        };
        bounds.height = height;
    }

    draw {
        var stream = pattern.asStream;
        var t = 0;
        var last = IdentityDictionary.new;
        var x;
        var yofs = 0;
        plotSpecs.do {|plot|
            var y2;
            var lbl = plot.label ?? {if(plot.y.class==Association) {plot.y.key}};
            yofs = yofs + plot.padding;
            y2 = round(bounds.height-yofs-plot.height-plot.padding)+0.5;

            lbl !? {
            Pen.font = Font.monospace(9);
                Pen.color = plot.labelColor;
                Pen.stringAtPoint(lbl,(xmargin+2)@y2); // print label in plot
            };

    /*        Pen.line(xmargin@y2,(length*xscale+xmargin)@y2);
            Pen.width = 1;
            Pen.strokeColor = Color.grey(0.5);
            Pen.stroke;*/
            yofs = yofs + plot.height+plot.padding;
        };

        while { t<length } {
            stream.next(Event.default).use {|ev|
                var lastP=0@inf, firstP=0@0;
                yofs = 0;
                x = round(t * xscale) + 0.5 + xmargin;

                plotSpecs.do {|plot,i|
                    var h = plot.height;
                    var y, lastDot, dotSize;

                    yofs = yofs + plot.padding;
                    y = (bounds.height-round(yofs+(this.parmap(ev,plot.y)*h))+0.5).asArray;

                    last[i] = max(last[i].size,y.size).collect {|n|
                        var old = if(last[i].notNil) {last[i].clipAt(n)};
                        var p = x @ y.clipAt(n);

                        if(i==0 and: {p.y > firstP.y}) { firstP = p };

                        Pen.strokeColor = this.parmap(ev,plot.color);
                        Pen.width = this.parmap(ev,plot.lineWidth);
                        Pen.lineDash = this.parmap(ev,plot.dash);

                        switch(plot.type,
                            \linear, {
                                if(old.isNil) {
                                    Pen.moveTo(p);
                                } {
                                    Pen.line(old,p);
                                };
                                Pen.stroke;
                                old = p;
                            },
                            \steps, {
                                if(old.isNil) {
                                    Pen.moveTo(p);
                                } {
                                    Pen.line(old,p);
                                };
                                Pen.lineTo(old = p + (round(ev.delta * xscale) @ 0));
                                Pen.stroke;
                            },
                            \levels, {
                                if(lastDot != p) {
                                    Pen.line(p, p + ((ev[plot.lenKey].value * xscale) @ 0));
                                    Pen.stroke;
                                };
                                old = nil;
                            },
                            \dots, {
                                old = nil;
                            }
                        );
                        dotSize = this.parmap(ev,plot.dotSize);
                        if(dotSize>0 and: {lastDot != p}) {
                            Pen.fillColor = this.parmap(ev,plot.dotColor);
                            Pen.addArc(p, dotSize, 0, 2pi);
                            Pen.fill;
                        };
                        lastDot = p;
                        if(p.y < lastP.y) {lastP = p};

                        old;
                    }.clipExtend(y.size);

                    yofs = yofs + h + plot.padding;
                };

                if(tickFullHeight) {
                    Pen.line(x@0,x@bounds.height);
                } {
                    Pen.line(firstP,lastP);
                };
                Pen.width = 1;
                Pen.strokeColor = tickColor;
                Pen.lineDash = tickDash;
                Pen.stroke;

                t = t + ev.delta;
            }
        }
    }
}
