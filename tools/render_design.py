"""Render deterministic design boards (not device screenshots). Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / 'previews'
OUT.mkdir(parents=True, exist_ok=True)
FONT = next((p for p in (Path('C:/Windows/Fonts/msyh.ttc'), Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')) if p.exists()), None)
if FONT is None:
    raise SystemExit('Install Microsoft YaHei or Noto Sans CJK before rendering.')
BLUE, BG, INK, MUTED, LINE = '#1688F8', '#F7F8FA', '#202124', '#737983', '#E9EDF2'
S = 2

def page(kind, status='ready'):
    image = Image.new('RGB', (390*S, 844*S), BG if kind != 'login' else 'white')
    d = ImageDraw.Draw(image)
    def text(x,y,t,size=16,color=INK):
        d.text((x*S,y*S), t, fill=color, font=ImageFont.truetype(str(FONT), size*S))
    def rect(box,fill='white',radius=12,outline=None):
        d.rounded_rectangle(tuple(int(v*S) for v in box), radius*S, fill, outline=outline, width=S)
    def line(x,y,x2,y2,color=LINE,width=1):
        d.line((x*S,y*S,x2*S,y2*S), fill=color,width=width*S)
    def button(y,label,color=BLUE):
        rect((20,y,370,y+52),color,10)
        bbox=d.textbbox((0,0),label,font=ImageFont.truetype(str(FONT),16*S))
        text((390-(bbox[2]-bbox[0])/S)/2,y+14,label,16,'white')
    text(24,12,'9:41',13)
    text(310,12,'5G  ▰',13)
    if kind == 'login':
        rect((20,105,84,169),BLUE,16)
        line(36,137,47,148,'white',4); line(47,148,69,124,'white',4)
        text(20,195,'福大签到助手',26)
        text(20,239,'使用统一身份认证账号登录',15,MUTED)
        text(20,303,'账号',14)
        rect((20,333,370,389),BG,10)
        text(36,349,'请输入学校账号',15,MUTED)
        text(20,414,'密码',14)
        rect((20,444,370,500),BG,10)
        text(36,460,'请输入密码',15,MUTED); text(320,461,'显示',13,BLUE)
        button(535,'登录')
        text(89,611,'账号密码加密保存在本机',13,MUTED)
        text(113,779,'非官方校园工具',12,MUTED)
    else:
        text(20,60,'晚点名' if kind=='home' else '我的',22)
        if kind=='home':
            text(337,65,'刷新',14,BLUE)
            text(20,112,'11月6日  星期五 · 示例',13,MUTED)
            text(20,143,'你好，张同学',22)
            labels={'ready':('今日未签到','在允许的时间内点击签到',BLUE),'success':('签到成功','已从服务器确认今日签到','#16865A'),'processing':('签到处理中','请稍后刷新查看结果','#AD6500'),'failure':('暂时无法签到','网络连接失败，请刷新后重试','#C83B3B')}
            title,desc,color=labels[status]
            rect((20,197,370,302))
            d.ellipse((38*S,220*S,48*S,230*S),fill=color)
            text(60,211,title,20,color); text(38,258,desc,13,MUTED)
            rect((20,318,370,460))
            for idx,(label,value) in enumerate([('签到时间','21:00 — 23:00'),('签到方式','校内'),('所属学院','示例学院')]):
                y=336+idx*43
                text(36,y,label,14,MUTED); text(166,y,value,14)
                if idx<2: line(36,y+32,354,y+32)
            rect((20,476,370,592))
            text(36,492,'签到地点',14,MUTED);text(36,521,'示例校区',16)
            text(36,554,'使用配置坐标',13,MUTED)
            if status=='success':
                text(20,611,'签到时间  21:12:08（示例）',13,'#16865A')
                button(647,'已签到','#16865A')
            else: button(631,'签到' if status=='ready' else '刷新状态')
            text(114,709,'最近查询 21:12 · 示例',12,MUTED)
        else:
            rect((20,118,370,224))
            text(36,136,'当前账号',13,MUTED);text(36,166,'24*****01',20);text(284,172,'更换账号',13,BLUE)
            rect((20,244,370,410))
            for idx,label in enumerate(['清除本机数据','使用说明','源码仓库']):
                text(36,260+idx*54,label,16);text(338,260+idx*54,'›',20,MUTED)
                if idx<2:line(36,299+idx*54,354,299+idx*54)
            rect((20,430,370,554))
            text(36,447,'关于福大签到助手',16)
            text(36,480,'版本 1.0.0',13,MUTED)
            text(36,512,'非学校或福 uu 官方客户端',13,MUTED)
        rect((0,766,390,844),'white',0); line(0,766,390,766)
        for x,label,active in [(98,'签到',kind=='home'),(292,'我的',kind=='profile')]:
            color=BLUE if active else MUTED
            d.ellipse(((x-8)*S,780*S,(x+8)*S,796*S),outline=color,width=2*S)
            text(x-13,802,label,13,color)
    line(146,834,244,834,INK,4)
    return image

items=[('login',page('login')),('home',page('home')),('profile',page('profile')),
       ('home-success',page('home','success')),('home-processing',page('home','processing')),('home-failure',page('home','failure'))]
board=Image.new('RGB',(3*820,2*1770),'#E9EDF2')
for i,(name,img) in enumerate(items):
    img.save(OUT/f'{name}.png')
    board.paste(img,((i%3)*820+20,(i//3)*1770+50))
    ImageDraw.Draw(board).text(((i%3)*820+20,(i//3)*1770+10),name,fill=INK,font=ImageFont.truetype(str(FONT),26))
board.save(OUT/'overview.png')
print('Rendered six design previews and overview.')
