const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{cards:[]},async load(){const me=await api.request('/me');this.setData({cards:me.cards.map(f.decorate)})}})
