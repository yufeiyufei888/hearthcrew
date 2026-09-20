package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.List;
import java.util.ArrayList;

/** GUI-scaled pixels. Rendering, scrolling and hit-testing share these exact rectangles. */
public final class TaskBoardLayout {
    public record Card(int x,int y,int width,int height) {
        public boolean contains(double px,double py){return px>=x&&px<x+width&&py>=y&&py<y+height;}
    }
    public static List<Card> cards(int x,int y,int width,int count,int height){
        if(width<1||count<0||count>3||height<1)throw new IllegalArgumentException("invalid task layout");
        int cols=width>=480?3:1,gap=6,w=(width-gap*(cols-1))/cols;
        var cards=new ArrayList<Card>();for(int n=0;n<count;n++)cards.add(new Card(x+n%cols*(w+gap),y+n/cols*(height+gap),w,height));return List.copyOf(cards);
    }
    private TaskBoardLayout(){}
}
