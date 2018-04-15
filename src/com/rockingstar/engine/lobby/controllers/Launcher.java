package com.rockingstar.engine.lobby.controllers;

import com.rockingstar.engine.ServerConnection;
import com.rockingstar.engine.command.client.*;
import com.rockingstar.engine.game.AbstractGame;
import com.rockingstar.engine.game.Lech;
import com.rockingstar.engine.game.OverPoweredAI;
import com.rockingstar.engine.game.Player;
import com.rockingstar.engine.gui.controllers.GUIController;
import com.rockingstar.engine.io.models.Util;
import com.rockingstar.engine.lobby.models.LobbyModel;
import com.rockingstar.engine.lobby.views.LobbyView;
import com.rockingstar.engine.lobby.views.LoginView;
import com.rockingstar.modules.Reversi.controllers.ReversiController;
import com.rockingstar.modules.TicTacToe.controllers.TTTController;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.LinkedList;

public class Launcher {

    private GUIController _guiController;

    private LobbyModel _model;
    private LobbyView _lobbyView;
    private ServerConnection _serverConnection;

    private LoginView _loginView;

    private AbstractGame _currentGame;

    private static Launcher _instance;

    private Player _localPlayer;

    public static final Object LOCK = new Object();

    private Thread _updatePlayerList;

    private LinkedList<Player> _onlinePlayers;

    private Launcher(GUIController guiController, ServerConnection serverConnection) {
        _guiController = guiController;
        _serverConnection = serverConnection;

        _model = new LobbyModel(this);
        _loginView = new LoginView();

        _model.addLoginActionHandlers(_loginView, this);
        _onlinePlayers = new LinkedList<>();

        _updatePlayerList = new Thread(() -> {
            while (_currentGame == null) {
                getPlayerList();

                try {
                    Thread.sleep(5000);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static Launcher getInstance() {
        if (_instance == null)
            return null;

        return _instance;
    }

    public static Launcher getInstance(GUIController guiController, ServerConnection serverConnection) {
        if (_instance == null)
            _instance = new Launcher(guiController, serverConnection);

        return _instance;
    }

    public void setCentralNode() {
        _guiController.setCenter(_loginView.getNode());
    }

    public void returnToLobby() {
        _guiController.setCenter(_lobbyView.getNode());
        _currentGame = null;
        _updatePlayerList.start();
    }

    private void loadModule(AbstractGame game) {
        _currentGame = game;
        Platform.runLater(() -> _guiController.setCenter(game.getView()));
    }

    public void handleLogin(String username, String gameMode, boolean isAI, String difficulty) {
        if (isAI){
              if (difficulty.equals("Lech")){
                  Util.displayStatus(difficulty + " Lech is AI");
                  _localPlayer = new Lech(username, new Color(0.5, 0.5, 0.5, 0));
              } else {
                  Util.displayStatus(difficulty + " Bas is AI");
                  _localPlayer = new OverPoweredAI(username, new Color(0.5,0.5,0.5,0));
              }
        } else {
              _localPlayer = new Player(username, new Color(0.5, 0.5, 0.5, 0));
        }

        if (_localPlayer.login()) {
            _lobbyView = new LobbyView(this);

            _lobbyView.setGameMode(gameMode);
            _lobbyView.setUsername(_localPlayer.getUsername());
            _model.setLocalPlayer(_localPlayer);

            _lobbyView.setPlayerList(_onlinePlayers);

            getPlayerList();
            getGameList();

            _lobbyView.setup();

            _guiController.setCenter(_lobbyView.getNode());
            _guiController.addStylesheet("lobby");

            _updatePlayerList.start();
        }
    }

    public void challengeReceived(String response) {
        String[] parts = response.replaceAll("[^a-zA-Z0-9 ]","").split(" ");

        String challenger = parts[1];
        int challengeNumber;
        String gameType = parts[5];

        try {
            challengeNumber = Integer.parseInt(parts[3]);
        }
        catch (NumberFormatException e) {
            System.out.println("Invalid challenge");
            return;
        }

        Platform.runLater(() -> {
            Alert challengeInvitationAlert = new Alert(Alert.AlertType.CONFIRMATION);
            challengeInvitationAlert.setTitle("Challenge received");
            challengeInvitationAlert.setHeaderText(null);
            challengeInvitationAlert.setContentText("Player " + challenger + " has invited you to a game of " + gameType + ". Do you accept?");

            challengeInvitationAlert.showAndWait();

            if (challengeInvitationAlert.getResult() == ButtonType.OK) {
                CommandExecutor.execute(new AcceptChallengeCommand(_serverConnection, challengeNumber));
                Util.displayStatus("Accepting challenge from " + challenger);
            }
        });
    }

    public void startMatch(String response) {
        String[] parts = response.replaceAll("[^a-zA-Z0-9 ]","").split(" ");

        String startingPlayer = parts[1];
        String gameType = parts[3];
        String opponentName = parts[5];

        Player opponent = new Player(opponentName);

        System.out.println("Entered lock-protected area in launcher");
        AbstractGame gameModule;
        switch (gameType) {
            case "Tic-tac-toe":
            case "Tictactoe":
                gameModule = new TTTController(_localPlayer, opponent);
                break;
            case "Reversi":
                gameModule = new ReversiController(_localPlayer, opponent);
                ((ReversiController) gameModule).setStartingPlayer(startingPlayer.equals(opponentName) ? opponent : _localPlayer);
                break;
            default:
                Util.displayStatus("Failed to load game module " + gameType);
                return;
        }

        Util.displayStatus("Loading game module " + gameType, true);

        loadModule(gameModule);
        gameModule.startGame();
        System.out.println("Left lock-protected area in Launcher");
    }

    private void getPlayerList() {
        CommandExecutor.execute(new GetPlayerListCommand(ServerConnection.getInstance()));
    }

    private void getGameList() {
        ServerConnection serverConnection = ServerConnection.getInstance();
        CommandExecutor.execute(new GetGameListCommand(serverConnection));
    }

    public void updatePlayerList(String response) {
        HashMap<String, Player> playerNames = new HashMap<>();
        HashMap<String, Player> loggedInPlayers = new HashMap<>();

        for (Player player : _onlinePlayers)
            playerNames.put(player.getUsername(), player);

        for (String player : Util.parseFakeCollection(response)) {
            if (!playerNames.keySet().contains(player))
                loggedInPlayers.put(player, new Player(player));
            else
                loggedInPlayers.put(player, playerNames.get(player));
        }

        _onlinePlayers.clear();
        _onlinePlayers.addAll(loggedInPlayers.values());
        _lobbyView.setPlayerList(_onlinePlayers);
    }

    public void updateGameList(String response) {
        LinkedList<String> games = new LinkedList<>();
        games.addAll(Util.parseFakeCollection(response));
        _lobbyView.setGameList(games);
    }

    public AbstractGame getGame() {
        return _currentGame;
    }

    public void subscribeToGame(String game) {
        CommandExecutor.execute(new SubscribeCommand(ServerConnection.getInstance(), game));
    }
}