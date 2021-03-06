package server.controller.playerControls;

import client.controller.ResearchCommand;
import engine.general.network.DisplayCommunicator;
import client.controller.MarketCommand;
import client.controller.RegionCommand;
import server.model.UpgradeDefinition;
import server.model.playerData.RegionBorder;
import engine.general.utility.Line;
import server.model.mapData.MapFacade;
import server.clientCom.RegionRenderData;
import server.model.mapData.ResourceMarket;
import server.clientCom.PlayerStats;
import server.model.playerData.Region;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import server.model.playerData.Player;
import client.controller.ClickCommand;

public class HumanPlayer extends Player {

    //These two values keep track of the clicks made. Use accessor methods to get the values in these variables.
    private Point rightClickA;
    private Point rightClickB;

    private final Point EMPTY_CLICK=new Point(-1,-1);
    //These variables are here to prevent concurrent access to the input variables.
    private final Semaphore rightClickLock;
    private final Semaphore leftClickLock;
    DisplayCommunicator displayCom;  //This object is used to communicate with the display.

    public HumanPlayer(DisplayCommunicator dCom){ 
    	super();
        displayCom=dCom;
        rightClickLock=new Semaphore(1);
        leftClickLock=new Semaphore(1);
    }

    /**
     * This method is used to send data to the client.
     * @param data The data that is being sent to the client.
     */
    public void sendData(Object data){
        try{
            displayCom.flushInput();
            if(data instanceof RegionRenderData){
                RegionRenderData regionData=(RegionRenderData)data;
                displayCom.writeToClient(regionData);
            }
            else{
                displayCom.writeToClient(data);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    public void processCommand(MarketCommand c){
        ResourceMarket market=MapFacade.getResourceMarket();
        if(c.instruction.equals(MarketCommand.SELL)){
            //Attempt to sell a resource and regenHP the market if successful.
            if(myResources().sell(c.resource,market.getPrice(c.resource))){
                market.sell(c.resource);
            }
        }
        if(c.instruction.equals(MarketCommand.BUY)){
            //Attempt to buy a resource and regenHP the market if successful.
            if(myResources().buy(c.resource,market.getPrice(c.resource))){
                market.buy(c.resource);
            }
        }
    }

    /**
     * This method processes command when a player clicks on the interface for a region.
     * @param c
     */
    public void processCommand(RegionCommand c){
        String name=c.name;
        for(Region r:myRegions()){
            if(r.getName()==name){
                String command=c.command;
                for(UpgradeDefinition u:UpgradeDefinition.upgradeList){
                    if(command.equals(u.getName())){
                        buildUpgrade(r,u);
                        return;
                    }
                }
            }
        }
    }

    public void processCommand(ResearchCommand c){
        int cost=(int)myResearch().getCost(c.command);
        if(myResources().buyResearch(cost)){
            myResearch().upgrade(c.command);
        }
    }

    private void buildUpgrade(Region r,UpgradeDefinition u){
        if(myResources().canUpgrade(u.getCost())){
            myResources().buyUpgrade(u);
            r.addUpgrade(u);
        }
    }

    /**
     * This method is used to listen to input from the client
     */
    public void clientListen(){
        try{
            Object serverInput;
            while((serverInput=displayCom.readFromClient())!=null){
                if(serverInput instanceof ClickCommand){
                	
                    ClickCommand input=(ClickCommand)serverInput;
                    if(input.clickType==ClickCommand.RIGHT_CLICK){
                        if(getRightClickA()==null)
                            setRightClickA(input.x,input.y);
                        else
                            setRightClickB(input.x,input.y);
                    }

                    /*
                    If the user left clicks on something, then the saved right clicks should be deleted.
                    Also, we need to turn on the interface for doing things with a region.
                     */
                    if(input.clickType==ClickCommand.LEFT_CLICK){               
                        rightClickA=null;
                        rightClickB=null;
                    }
                }

                if(serverInput instanceof ResearchCommand){
                    processCommand((ResearchCommand)serverInput);
                }
                if(serverInput instanceof RegionCommand){
                    processCommand((RegionCommand)serverInput);
                }
                if(serverInput instanceof MarketCommand){
                    processCommand((MarketCommand)serverInput);
                }
                if(serverInput instanceof KeyEvent){
                    int keyCommand=((KeyEvent) serverInput).getKeyCode();
                    UpgradeDefinition upgrade=UpgradeDefinition.getUpgrade(keyCommand);
                    buildUpgrade(upgrade);
                }
                processMoveCommand();
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }

    /**
     * This method sends statistics that indicate how well a player is doing.
     */
    public void sendStatistics(){

        HashMap<String,Double>  income=myResources().getIncome();
        double upkeep=myResources().getUpkeepCost(getRegionCount(),countTroops());
        HashMap<String,Integer> resources=myResources().getResourceData();
        int elapsedTime=MapFacade.getElapsedTime();
        //Send data about how well each player is doing.
        HashSet<Line> rallyData=new HashSet<Line>();
        
        for(HashSet<RegionBorder> com:myBorders().values()){
            for(RegionBorder connection:com){
                if(connection.isOn()){
                    Region destination=connection.getDestination();
                    Region start=connection.getOrigin();
                    short x=(short)start.xCenterRender();
                    short y=(short)start.yCenterRender();
                    short x2=(short)destination.xCenterRender();
                    short y2=(short)destination.yCenterRender();
                    rallyData.add((new Line.LineBuilder())
                    				.x1(x)
                    				.y1(y)
                    				.x2(x2)
                    				.y2(y2)
                    				.build());
                }
            }
        }
        
        PlayerStats stats=(new PlayerStats.Builder()).
        						resourceData(resources)
        						.timeElapsed(elapsedTime)
        						.playerScore(getScore())
        						.rallyPoints(rallyData)
        						.rightClick(rightClickA)
        						.rightClick(rightClickB)
        						.upkeep(upkeep)
        						.income(income)
        						.fails(myResources().getFailMessages())
        						.research(myResearch())
        						.build();
        try{
            displayCom.writeToClient(stats);
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
    
    public void setRightClickA(int x,int y){
        try{
            leftClickLock.acquire();
            rightClickA =new Point(x,y);
            leftClickLock.release();
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
    
    public void setRightClickB(int x,int y){
        try{
            rightClickLock.acquire();
            rightClickB=new Point(x,y);
            rightClickLock.release();
        }
        catch(Exception e){
            e.printStackTrace();
        }

    }
    
    public Point getRightClickA() {
        try{
            leftClickLock.acquire();
            if(rightClickA ==null){
                leftClickLock.release();
                return null;
            }
            Point leftCopy=new Point((int) rightClickA.getX(),(int) rightClickA.getY());
            leftClickLock.release();
            return leftCopy;
        }
        catch(Exception e){
            e.printStackTrace();
        }
        return EMPTY_CLICK;
    }
    public Point getRightClickB(){
        try{
            rightClickLock.acquire();
            if(rightClickB==null){
                rightClickLock.release();
                return null;
            }
            Point rightCopy=new Point((int)rightClickB.getX(),(int)rightClickB.getY());
            rightClickLock.release();
            return rightCopy;
        }
        catch(Exception e){
            e.printStackTrace();
        }
        return EMPTY_CLICK;
    }

    private Region findRegion(Point loc){
        ArrayList<Region> mapRegions= MapFacade.getRegions();
        for(Region r:mapRegions){
            if(r.getBounds().contains(loc)){
                return r;
            }
        }
        return null;
    }

    /**
     * This method processes user input for move commands.
     * TODO: Figure out what to do when a player clicks on a point that is
     * not within a region.
     */
    private void processMoveCommand() {

        Point clickA=getRightClickA();
        Point clickB=getRightClickB();
        if(clickA==null||clickB==null)
            return;

        //Get the regions for the move command.
        Region start=findRegion(clickA);
        Region end=findRegion(clickB);
       
        synchronized(myRegions()){
            if(start==end&&start!=null){
                clearRallyPoints(start);//Cancel movement between regions.
            }
            else if(start!=null&&end!=null){
                if(start.getBorderRegions().contains(end)){
                    setRallyPoint(start,end);
                }
            }
        }
        rightClickA=null;
        rightClickB=null;
    }

    public void buildUpgrade(UpgradeDefinition upgrade){
        //TODO:Determine if a player has enough resources to build an upgrade
        myResources().buyUpgrade(upgrade);
        findRegion(rightClickA).addUpgrade(upgrade);
    }
}
