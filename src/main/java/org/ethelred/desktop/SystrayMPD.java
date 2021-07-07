package org.ethelred.desktop;

import org.bff.javampd.player.Player;
import org.bff.javampd.playlist.PlaylistBasicChangeEvent;
import org.bff.javampd.server.MPD;
import org.bff.javampd.server.MPDConnectionException;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.io.*;

import javax.swing.JOptionPane;

/**
 * TODO
 *
 * @author eharman
 * @since 2021-02-25
 */
public class SystrayMPD
{
    public static void main(String[] args)
    {
        new SystrayMPD();
    }

    public String getMpdHost()
    {
        return mpdHost;
    }

    public int getMpdPort()
    {
        return mpdPort;
    }

    public boolean isShowNotifications()
    {
        return showNotifications;
    }

    public void updatePreferences(String host, int port, boolean showNotifications)
    {
        boolean serverChange = !Objects.equals(host, mpdHost) || port != mpdPort;
        this.mpdHost = host;
        this.mpdPort = port;
        this.showNotifications = showNotifications;
        _savePreferences();
        if (serverChange)
        {
            _reconnect();
        }
    }

    enum AppImage {
        disconnected("sync-solid"),
        play("play-solid"),
        pause("pause-solid"),
        next("step-forward-solid");

        private final String filename;
        private volatile Image image;

        AppImage(String filename)
        {
            this.filename = filename;
        }

        Image get()
        {
            if (image == null)
            {
                synchronized (this)
                {
                    if (image == null)
                    {
                        image = _getImage("/" + filename + ".png");
                    }
                }
            }
            return image;
        }
    }

    enum State {
        disconnected {
            @Override
            public AppImage image()
            {
                return AppImage.disconnected;
            }

            @Override
            public String toolTip(SystrayMPD systrayMPD)
            {
                return "Disconnected";
            }

            @Override
            public void action(SystrayMPD systrayMPD)
            {
                systrayMPD._reconnect();
            }
        }, playing
                {
                    @Override
                    public AppImage image()
                    {
                        return AppImage.pause;
                    }

                    @Override
                    public String toolTip(SystrayMPD systrayMPD)
                    {
                        return systrayMPD._handleSongChange();
                    }

                    @Override
                    public void action(SystrayMPD systrayMPD)
                    {
                        systrayMPD.mpd.getPlayer().pause();
                    }
                }, paused
                {
                    @Override
                    public AppImage image()
                    {
                        return AppImage.play;
                    }

                    @Override
                    public String toolTip(SystrayMPD systrayMPD)
                    {
                        return "Paused";
                    }

                    @Override
                    public void action(SystrayMPD systrayMPD)
                    {
                        systrayMPD.mpd.getPlayer().play();
                    }
                };

        public abstract AppImage image();
        public abstract String toolTip(SystrayMPD systrayMPD);
        public abstract void action(SystrayMPD systrayMPD);
    }
    private State state;

    private TrayIcon icon; // we only have one

    private String mpdHost;
    private int mpdPort;
    private boolean showNotifications;

    private MPD mpd;
    private final Map<MenuItem, Predicate<SystrayMPD>> menuConditions = new HashMap<>();

    public SystrayMPD()
    {
        try
        {
            _loadPreferences();
            var systray = SystemTray.getSystemTray();
            var menu = _buildMenu();
            icon = new TrayIcon(AppImage.disconnected.get(), "Disconnected", menu);
            icon.addActionListener(this::_action);
            systray.add(icon);

            _reconnect();
        }
        catch (Throwable e)
        {
            _report("init", e);
        }
    }

    private void _reconnect()
    {
        try
        {
            if (mpd != null)
            {
                try
                {
                    mpd.close();
                } catch (Exception e) {
                    _report("mpd.close()", e);
                }
            }
            mpd = new MPD.Builder()
                    .server(mpdHost)
                    .port(mpdPort)
                    .build();
            var monitor = mpd.getMonitor();
            monitor.addPlayerChangeListener(evt -> {
                switch (evt.getStatus())
                {
                    case PLAYER_STARTED:
                    case PLAYER_UNPAUSED:
                        _enter(State.playing);
                        break;
                    case PLAYER_STOPPED:
                    case PLAYER_PAUSED:
                        _enter(State.paused);
                        break;
                }
            });
            monitor.addConnectionChangeListener(evt -> {
                if (!evt.isConnected())
                {
                    _enter(State.disconnected);
                }
            });
            monitor.addPlaylistChangeListener(evt -> {
                if (evt.getEvent() == PlaylistBasicChangeEvent.Event.SONG_CHANGED)
                {
                    _handleSongChange();
                }
            });
            monitor.start();
            var status = mpd.getPlayer().getStatus();
            if (status == Player.Status.STATUS_PLAYING)
            {
                _enter(State.playing);
            } else
            {
                _enter(State.paused);
            }
        }
        catch (MPDConnectionException e)
        {
            _report("reconnect", e);
            _enter(State.disconnected);
        }
    }

    private String _handleSongChange()
    {
        var song = mpd.getPlayer().getCurrentSong();
        var songText = song.getArtistName() + " - " + song.getTitle();
        icon.setToolTip(songText);
        if (showNotifications)
        {
            icon.displayMessage("Now Playing", songText, TrayIcon.MessageType.INFO);
        }
        return songText;
    }

    private void _enter(State state)
    {
        if (this.state == state)
        {
            return;
        }
        _debug("Enter state " + state);
        this.state = state;
        icon.setImage(state.image().get());
        icon.setToolTip(state.toolTip(this));
        _menuUpdate();
    }

    private void _loadPreferences()
    {
        var node = Preferences.userNodeForPackage(SystrayMPD.class);
        mpdHost = node.get("mpdHost", "localhost");
        mpdPort = node.getInt("mpdPort", 6600);
        showNotifications = node.getBoolean("mpdNotifications", true);
        _debug(mpdHost + ":" + mpdPort);
    }

    private void _savePreferences()
    {
        var node = Preferences.userNodeForPackage(SystrayMPD.class);
        node.put("mpdHost", mpdHost);
        node.putInt("mpdPort", mpdPort);
        node.putBoolean("mpdNotifications", showNotifications);
    }

    private void _menuUpdate()
    {
        menuConditions.forEach((mi, pred) -> mi.setEnabled(pred.test(this)));
    }

    private PopupMenu _buildMenu()
    {
        var menu = new PopupMenu();
        menu.add(_menuItem("Play", evt -> State.paused.action(this), x -> x.state == State.paused));
        menu.add(_menuItem("Pause", evt -> State.playing.action(this), x -> x.state == State.playing));
        menu.add(_menuItem("Next", evt -> mpd.getPlayer().playNext(), x -> x.state == State.playing));
        menu.addSeparator();
        menu.add(_menuItem("Preferences...", evt -> new org.ethelred.desktop.Preferences(this).show(), x -> true));
        menu.add(_menuItem("Quit", evt -> System.exit(0), x -> true));
        return menu;
    }

    private MenuItem _menuItem(String label, ActionListener actionListener, Predicate<SystrayMPD> condition)
    {
        var mi = new MenuItem(label);
        mi.addActionListener(actionListener);
        menuConditions.put(mi, condition);
        return mi;
    }

    private void _action(ActionEvent actionEvent)
    {
        if (state == null)
        {
            _reconnect();
        }
        else
        {
            state.action(this);
        }
    }

    private static Image _getImage(String path)
    {
        return Toolkit.getDefaultToolkit().getImage(SystrayMPD.class.getResource(path));
    }

    private void _report(String when, Throwable e) {
        var sw = new StringWriter();
        var w = new PrintWriter(sw);
        w.append(when).append(": ");
        e.printStackTrace(w);
        JOptionPane.showMessageDialog(
            null, // unknown parent component
            sw.toString(),
            "systray-mpd error!",
            JOptionPane.ERROR_MESSAGE
        );
    }

    private void _debug(String message) {
        // JOptionPane.showMessageDialog(null, message);
    }
}
