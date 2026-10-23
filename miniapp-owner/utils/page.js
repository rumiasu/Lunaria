const api = require('./api')
function createPage(definition) {
  const methods = {
    data: { loading:false,busy:false,error:'',ready:false,...definition.data },
    async onShow() { try { this.setData({loading:true,error:''});const session=await api.refresh(); if(!session.authenticated){this.setData({ready:false});return} this.setData({ready:true});await this.load() } catch(e){this.setData({error:e.message})} finally{this.setData({loading:false})} },
    async demoLogin(e) { await this.run(async()=>{await api.login(e.currentTarget.dataset.user);this.setData({ready:true});await this.load()}) },
    async run(work) { if(this.data.busy)return;this.setData({busy:true,error:''});try{await work()}catch(e){this.setData({error:e.message});wx.showToast({title:e.message,icon:'none',duration:3000})}finally{this.setData({busy:false})} },
    async retry(){await this.onShow()},
    input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value})},
    go(e){wx.navigateTo({url:e.currentTarget.dataset.url})},
    ...definition
  }
  methods.data={loading:false,busy:false,error:'',ready:false,...definition.data}
  Page(methods)
}
module.exports = createPage
