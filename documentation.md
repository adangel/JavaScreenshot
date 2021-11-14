---
title: Taking screenshots with Java under Wayland
tags: java screenshot wayland dbus
---

# Taking screenshots with Java under Wayland

If you want to create a screenshot with Java, you usually use the robot class
[java.awt.Robot](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/Robot.html).
This works fine most of the time and is platform independent (as it should be).
However, running Java under [Wayland](https://wayland.freedesktop.org/), which
is the standard compositor for desktop environments such as [GNOME](https://www.gnome.org/),
the produced screenshot is just black. No error or anything.

It turns out, that this is actually a bug in Java, that is known already:
[JDK-8269245](https://bugs.openjdk.java.net/browse/JDK-8269245):
"[wayland] java.awt.Robot.createScreenCapture(r) produces black image".

But other tools like [GIMP](https://www.gimp.org/) or [Flameshot](https://flameshot.org/)
can take screenshots - why would Java not be able to do so?

As it is often the case, you learn most about how things work, when they start
to break. And these applications failed recently to take screenshots as well.
This has to do with a recent update of GNOME, which forbids arbitrary
applications to take screenshots of the desktop without letting the user know
about this.

But let's take a step back: On a modern linux desktop, the different process
such as the desktop itself and the applications communicate with each other
via [DBus](https://freedesktop.org/wiki/Software/dbus/). This means, an
application can send a request to the desktop to take a screenshot. This
is done via more or less well-defined APIs that the desktop provides.
For example, the GNOME desktop provided the service `org.gnome.Shell` with
the interface `org.gnome.Shell.Screenshot`. This interface is
defined in the XML file [org.gnome.Shell.Screenshot.xml](https://gitlab.gnome.org/GNOME/gnome-shell/-/blob/main/data/dbus-interfaces/org.gnome.Shell.Screenshot.xml):

```xml
<node>
    <interface name="org.gnome.Shell.Screenshot">
        <method name="Screenshot">
            <arg type="b" direction="in" name="include_cursor"/>
            <arg type="b" direction="in" name="flash"/>
            <arg type="s" direction="in" name="filename"/>
            <arg type="b" direction="out" name="success"/>
            <arg type="s" direction="out" name="filename_used"/>
        </method>
    </interface>
</node>
```

This is only an excerpt but it shows the one important method `Screenshot`
which creates a screenshot of the whole screen and stores it into a
file with the name given in the argument "filename".

With Gnome Shell 41 this DBus service is now considered to be a private
API only to be used by Gnome itself and not by any other applications.
If you try to create a screenshot with flameshot and have the tool
`dbus-monitor` running at the same time, you'll see the following output
in your console:

```
method call time=1636906285.148456 sender=:1.98 -> destination=org.gnome.Shell serial=33 path=/org/gnome/Shell/Screenshot; interface=org.gnome.Shell.Screenshot; member=Screenshot
   boolean false
   boolean false
   string "/tmp/2021-11-14_17-11.png"
error time=1636906285.149829 sender=:1.22 -> destination=:1.98 error_name=org.freedesktop.DBus.Error.AccessDenied reply_serial=33
   string "Screenshot is not allowed"
```

Sender ":1.98" try to call the method Screenshot on interface "org.gnome.Shell.Screenshot"
and got the response "Screenshot is not allowed" back.

You can use python to execute this DBus method call directly:

```python
import dbus
bus = dbus.SessionBus()
proxy = bus.get_object('org.gnome.Shell', '/org/gnome/Shell/Screenshot')
screenshot_iface = dbus.Interface(proxy, dbus_interface='org.gnome.Shell.Screenshot')
print(screenshot_iface.Screenshot(True,True,'target_filename.png'))
```

And you'll get the same error back:

    dbus.exceptions.DBusException: org.freedesktop.DBus.Error.AccessDenied: Screenshot is not allowed

And screenshot via GIMP also fails now. Flameshot handled this in issue
[flameshot#1910](https://github.com/flameshot-org/flameshot/issues/1910) and
there I found the pointer to the root cause: It was a merge request in
gnome shell with the title "dbus: Restrict callers of private D-Bus APIs".
You can read the info here: [gnome-shell!1970](https://gitlab.gnome.org/GNOME/gnome-shell/-/merge_requests/1970)
and additional background on [gnome-shell#3943](https://gitlab.gnome.org/GNOME/gnome-shell/-/issues/3943).
They made sure, that the own Gnome utilities (like Gnome Screenshot) still works
(and the shortcut). But this gnome shell API is officially private now.

What are the alternatives then? They talk about "portals". And the
correct API to use for screenshots is the portals API from
[xdg-desktop-portal](https://flatpak.github.io/xdg-desktop-portal/portal-docs.html#gdbus-org.freedesktop.portal.Screenshot).
This is also a DBus service and using this, a little dialog will be shown allowing
the user to grant permission to take the screenshot. This is to prevent applications
from silently spying on their users.

On Stackoverflow [Using freedesktop portal to take screenshots with Python](https://stackoverflow.com/questions/56368170/using-freedesktop-portal-to-take-screenshots-with-python)
there is a hint, on how to use this in python. Here's a full example, that
can take a screenshot:

```python
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib
import dbus

DBusGMainLoop(set_as_default=True)
loop = GLib.MainLoop()

bus = dbus.SessionBus()

def handler(*args, **kwargs):
    print('--- handler ---')
    print("got signal: response: %s result: %s" % (str(args[0]), str(args[1])))
    if args[0] == 0:
      print("uri: %s" % args[1]['uri'])
    else:
      print('user cancelled or timeout')
    loop.quit()

bus.add_signal_receiver(handler, signal_name='Response', dbus_interface='org.freedesktop.portal.Request')

proxy = bus.get_object('org.freedesktop.portal.Desktop', '/org/freedesktop/portal/desktop')
screenshot_iface = dbus.Interface(proxy, dbus_interface='org.freedesktop.portal.Screenshot')
request = screenshot_iface.Screenshot('', {'interactive': False})
print(request)

loop.run()
```

It is a bit more involved, because the screenshot method is not synchronous anymore.
You don't get back the screenshot directly, but only after the user shared the
screenshot with your application. This is done via a DBus signal onto which
you need to subscribe. The user could also cancel the screenshot.

These are the resources I used for the python snippet:
[dbus-python tutorial](https://dbus.freedesktop.org/doc/dbus-python/tutorial.html),
[Python DBUS examples](https://github.com/stylesuxx/python-dbus-examples).

Another interesting tool is [dbus-send](https://dbus.freedesktop.org/doc/dbus-send.1.html).
You can use it to call simple services and methods from the command line directly.
It is however not suitable to execute the screenshot method due to the signal
handling, but DBus supports introspecting services, so you can request
the interface definition of the service you want to call:

```
dbus-send --print-reply=literal --dest=org.freedesktop.portal.Desktop\
    /org/freedesktop/portal/desktop\
    org.freedesktop.DBus.Introspectable.Introspect
```

This will give you the interface definition of `org.freedesktop.portal.Desktop`
in XML format, which is the [Introspection Data Format](https://dbus.freedesktop.org/doc/dbus-specification.html#introspection-format).

Another tool is [d-feet](https://wiki.gnome.org/Apps/DFeet) which
lets you browse the available services in your DBus session.

Now, what is possible with python should be possible with Java, shouldn't it?
There are many different [DBus bindings](https://www.freedesktop.org/wiki/Software/DBusBindings/)
for various languagues, including Java. But the official Java binding seems
to be old and unmaintained. But there is a fresh fork - this is [dbus-java by hypfvieh](https://github.com/hypfvieh/dbus-java).
This library is also available in maven central and just works. Until
you figured out how, of course. The [DBus-Java Quickstart](https://github.com/hypfvieh/dbus-java/blob/master/src/site/markdown/quick-start.md)
is helpful for this.

The approach in Java is very similar to the python approach and I implemented it
in [`ScreenshotUtil`](https://github.com/adangel/JavaScreenshot/blob/master/org/adangel/javascreenshot/ScreenshotUtil.java).

First we need to access our session bus:

```java
DBusConnection bus = DBusConnection.getConnection(DBusConnection.DBusBusType.SESSION);
```

Then we can register our signal handler that will be invoked when the user shares the screenshot:

```java
DBusMatchRule matchRule = new DBusMatchRule("signal", "org.freedesktop.portal.Request", "Response");
bus.addGenericSigHandler(matchRule, new DBusSigHandler<DBusSignal>() {
    @Override
    public void handle(DBusSignal t) {
        // ...
    }
});
```

And last we need to request the proxy object to actually call the screenshot method:

```java
ScreenshotInterface iface = bus.getRemoteObject("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", ScreenshotInterface.class);
Map<String, Variant> options = new HashMap<>();
options.put("interactive", new Variant(Boolean.FALSE));
options.put("handle_token", new Variant(token));
DBusPath result = iface.Screenshot("", options);
```

I've manually
created the interface [`ScreenshotInterface`](https://github.com/adangel/JavaScreenshot/blob/master/org/adangel/javascreenshot/dbus/ScreenshotInterface.java)
which represents the portal desktop API in java. The library also provides
a way to [generate code](https://github.com/hypfvieh/dbus-java/blob/master/src/site/markdown/code-generation.md)
directly:

```java
@DBusInterfaceName(value = "org.freedesktop.portal.Screenshot")
public interface ScreenshotInterface extends DBusInterface {
    DBusPath Screenshot(String parentWindow, Map<String, Variant> options);
}
```

I've put a little application around ScreenshotUtil which adds a
[SystemTray](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/SystemTray.html)
and a little preview window which directly displays the taken screenshot.
You can then save it into a file or take another screenshot.

The journey is going on: Switching to the Portal API to take screenshots will ask the user
always to share the screenshot with the application. That's annoying if the task of an application is
to take screenshots. There are discussions about a new API on github [xdg-desktop-portal#649: Screenshot portal without prompt](https://github.com/flatpak/xdg-desktop-portal/issues/649)
which allows the user to grant a permission to the application that requested the screenshot, so that for
future screenshots, no prompt is displayed anymore.
