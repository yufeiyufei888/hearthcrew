package io.github.yufeiyufei888.hearthcrew.kernel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TaskBoardLayoutTest {
 @Test void scaledWidthsPreserveThreeNonoverlappingHitTargets(){for(int width:new int[]{240,320,479,480,640,1024}){var cards=TaskBoardLayout.cards(8,76,width,3,160);assertEquals(3,cards.size());for(int n=0;n<3;n++){var c=cards.get(n);assertTrue(c.x()>=8&&c.x()+c.width()<=8+width);assertTrue(c.contains(c.x()+1,c.y()+1));assertFalse(c.contains(c.x()+c.width(),c.y()));for(int k=n+1;k<3;k++){var d=cards.get(k);assertTrue(c.x()+c.width()<=d.x()||d.x()+d.width()<=c.x()||c.y()+c.height()<=d.y()||d.y()+d.height()<=c.y());}}}}
 @Test void narrowViewScrollsVerticallyAndWideViewFitsOneRow(){var narrow=TaskBoardLayout.cards(0,0,300,3,160);assertEquals(332,narrow.get(2).y());var wide=TaskBoardLayout.cards(0,0,600,3,160);assertEquals(0,wide.get(2).y());}
}
