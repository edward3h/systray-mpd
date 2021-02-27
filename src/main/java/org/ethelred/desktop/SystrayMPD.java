package org.ethelred.desktop;

import org.bff.javampd.monitor.StandAloneMonitor;
import org.bff.javampd.player.Player;
import org.bff.javampd.playlist.PlaylistBasicChangeEvent;
import org.bff.javampd.server.MPD;
import org.bff.javampd.server.MPDConnectionException;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

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


    enum State {
        disconnected {
            @Override
            public String imageName()
            {
                return "disconnected";
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
                    public String imageName()
                    {
                        return "pause";
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
                    public String imageName()
                    {
                        return "play";
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

        public abstract String imageName();
        public abstract String toolTip(SystrayMPD systrayMPD);
        public abstract void action(SystrayMPD systrayMPD);
    }
    private State state;

    private final Map<String, Image> imageMap =
            Set.of("play", "pause", "disconnected").stream()
            .collect(Collectors.toMap(n -> n, n -> _getImage("/" + n + ".png")));
    private TrayIcon icon; // we only have one

    private String mpdHost;
    private int mpdPort;
    private boolean showNotifications;

    private MPD mpd;

    public SystrayMPD()
    {
        try
        {
            _loadPreferences();
            var systray = SystemTray.getSystemTray();
            var menu = _buildMenu();
            icon = new TrayIcon(imageMap.get("disconnected"), "Disconnected", menu);
            icon.addActionListener(this::_action);
            systray.add(icon);

            _reconnect();
        }
        catch (Exception e)
        {
            System.err.println("Caught an exception " + e.getMessage());
            e.printStackTrace(System.err);
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
                    // whatevs
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
        this.state = state;
        icon.setImage(imageMap.get(state.imageName()));
        icon.setToolTip(state.toolTip(this));
    }

    private void _loadPreferences()
    {
        var node = Preferences.userNodeForPackage(SystrayMPD.class);
        mpdHost = node.get("mpdHost", "localhost");
        mpdPort = node.getInt("mpdPort", 6600);
        showNotifications = node.getBoolean("mpdNotifications", true);
    }

    private void _savePreferences()
    {
        var node = Preferences.userNodeForPackage(SystrayMPD.class);
        node.put("mpdHost", mpdHost);
        node.putInt("mpdPort", mpdPort);
        node.putBoolean("mpdNotifications", showNotifications);
    }

    private PopupMenu _buildMenu()
    {
        var menu = new PopupMenu();
        menu.add(_menuItem("Preferences...", evt -> new org.ethelred.desktop.Preferences(this).show()));
        menu.add(_menuItem("Quit", evt -> System.exit(0)));
        return menu;
    }

    private MenuItem _menuItem(String label, ActionListener actionListener)
    {
        var mi = new MenuItem(label);
        mi.addActionListener(actionListener);
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

    private Image _getImage(String path)
    {
        return Toolkit.getDefaultToolkit().getImage(getClass().getResource(path));
    }

}
